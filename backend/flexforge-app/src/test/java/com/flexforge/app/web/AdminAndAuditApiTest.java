package com.flexforge.app.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P03 迭代 2 验收：管理员创建用户并分配角色、非管理员被拒（403 可诊断）、
 * 菜单三角色差异、审计查询过滤与分页白名单（docs/09 P03 验收标准）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AdminAndAuditApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminBearer;
    private String developerBearer;
    private String userBearer;

    @BeforeEach
    void seedAndLogin() {
        AuthTestSupport.seedUsers(jdbc);
        adminBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.ADMIN_USERNAME, AuthTestSupport.adminPassword());
        developerBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.DEVELOPER_USERNAME, AuthTestSupport.developerPassword());
        userBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.USER_USERNAME, AuthTestSupport.userPassword());
    }

    @Test
    void adminCreatesUserAndAssignsRolesWithAuditTrail() throws Exception {
        String body = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/system/users")
                        .header("Authorization", adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newcomer\",\"password\":\"fresh-pass-word\","
                                + "\"displayName\":\"新用户\",\"roles\":[\"USER\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("newcomer"))
                .andExpect(jsonPath("$.roles[0]").value("USER"))
                .andReturn().getResponse().getContentAsString();

        // 新用户可登录且角色生效（审计可追溯 + 角色实际生效）
        String token = AuthTestSupport.loginToken(mockMvc, "newcomer", "fresh-pass-word");
        assertThat(token).isNotBlank();

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE action = 'user.create'"
                        + " AND actor = ? AND result = 'success'",
                Integer.class, AuthTestSupport.ADMIN_USERNAME);
        assertThat(count).isGreaterThanOrEqualTo(1);

        // 权限变化：改角色并审计（JsonPath 小整数返回 Integer，按 int 读取）
        int userId = com.jayway.jsonpath.JsonPath.read(body, "$.id");
        mockMvc.perform(MockMvcRequestBuilders.put("/api/v1/system/users/" + userId + "/roles")
                        .header("Authorization", adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"DEVELOPER\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("DEVELOPER"));

        Integer roleChange = jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE action = 'user.roles.update'"
                        + " AND actor = ? AND object_id = ?",
                Integer.class, AuthTestSupport.ADMIN_USERNAME, Long.toString(userId));
        assertThat(roleChange).isEqualTo(1);
    }

    @Test
    void nonAdminCannotCreateUserOrAssignRoles() throws Exception {
        for (String bearer : new String[] {developerBearer, userBearer}) {
            mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/system/users")
                            .header("Authorization", bearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"hacker1\",\"password\":\"pass-word-x\","
                                    + "\"displayName\":\"x\",\"roles\":[\"USER\"]}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("permission_denied"))
                    .andExpect(jsonPath("$.requestId").exists());

            mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/system/users")
                            .header("Authorization", bearer))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("permission_denied"));
        }
        // 未创建成功：无残留用户
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM sys_user WHERE username LIKE 'hacker%'", Integer.class);
        assertThat(count).isEqualTo(0);
    }

    @Test
    void menusDifferByRole() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/menus").header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key == 'system-management')].title").value(hasItem("系统管理")))
                .andExpect(jsonPath("$[?(@.key == 'workbench')].key").value(hasItem("workbench")));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/menus").header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key == 'workbench')].key").value(hasItem("workbench")))
                .andExpect(jsonPath("$..key").value(not(hasItem("system-management"))));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/menus").header("Authorization", developerBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..key").value(not(hasItem("system-management"))));
    }

    @Test
    void auditQueryFiltersByActorAndActionWithPagination() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/system/audit-events")
                        .header("Authorization", adminBearer)
                        .queryParam("actor", AuthTestSupport.ADMIN_USERNAME)
                        .queryParam("action", "auth.login")
                        .queryParam("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].actor").value(AuthTestSupport.ADMIN_USERNAME))
                .andExpect(jsonPath("$.items[0].action").value("auth.login"))
                .andExpect(jsonPath("$.pageSize").value(10));

        // 非管理员不可查
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/system/audit-events")
                        .header("Authorization", userBearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("permission_denied"));

        // 排序白名单外拒绝（S1：无动态 SQL 注入面）
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/system/audit-events")
                        .header("Authorization", adminBearer)
                        .queryParam("sortBy", "password"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"));
    }

    @Test
    void userValidationRejectsBadInput() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/system/users")
                        .header("Authorization", adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"X\",\"password\":\"short\",\"displayName\":\"x\","
                                + "\"roles\":[\"SUPERROLE\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"));
    }
}
