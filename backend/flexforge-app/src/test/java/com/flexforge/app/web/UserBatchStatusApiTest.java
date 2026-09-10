package com.flexforge.app.web;

import com.jayway.jsonpath.JsonPath;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批量停启用回归（FR-AUTH-05，docs/09 P24）：成功路径（逐用户终态+逐用户审计+
 * 落库断言）与守卫失败路径（含自己/未知 id/超上限/空集/非管理员/幂等重入）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class UserBatchStatusApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminBearer;
    private String userBearer;

    @BeforeEach
    void seedAndLogin() throws Exception {
        AuthTestSupport.seedUsers(jdbc);
        adminBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.ADMIN_USERNAME, AuthTestSupport.adminPassword());
        userBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.USER_USERNAME, AuthTestSupport.userPassword());
    }

    private int createUser(String username) throws Exception {
        String body = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/system/users")
                        .header("Authorization", adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"pass-word-x1\","
                                + "\"displayName\":\"批量\",\"roles\":[\"USER\"]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private void batch(int expectedStatus, String body) throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/system/users/batch-status")
                        .header("Authorization", adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void batchBlocksUsersWithPerUserAuditTrail() throws Exception {
        int first = createUser("batchone");
        int second = createUser("batchtwo");
        int auditBefore = auditCount();

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/system/users/batch-status")
                        .header("Authorization", adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\":[" + first + "," + second + "],\"status\":\"BLOCKED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("BLOCKED"))
                .andExpect(jsonPath("$[1].status").value("BLOCKED"));

        // 落库断言 + 逐用户审计（词表与单人路径一致 user.status.update）
        Integer blocked = jdbc.queryForObject(
                "SELECT count(*) FROM sys_user WHERE id IN (?, ?) AND status = 'BLOCKED'",
                Integer.class, first, second);
        assertThat(blocked).isEqualTo(2);
        assertThat(auditCount()).isEqualTo(auditBefore + 2);

        // 幂等重入：同状态不写库但仍成功（audit 追加一行/人）
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/system/users/batch-status")
                        .header("Authorization", adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\":[" + first + "],\"status\":\"BLOCKED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("BLOCKED"));
        assertThat(auditCount()).isEqualTo(auditBefore + 3);
    }

    private int auditCount() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE action = 'user.status.update'"
                        + " AND actor = ?", Integer.class, AuthTestSupport.ADMIN_USERNAME);
        return count == null ? 0 : count;
    }

    @Test
    void guardsRejectSelfUnknownOversizedAndEmpty() throws Exception {
        Long adminId = jdbc.queryForObject(
                "SELECT id FROM sys_user WHERE username = ?", Long.class,
                AuthTestSupport.ADMIN_USERNAME);
        int someone = createUser("batchguard");
        // 含自己：整批拒绝且无写入
        batch(400, "{\"userIds\":[" + someone + "," + adminId + "],\"status\":\"BLOCKED\"}");
        // 未知 id：404（与单人路径同口径），不产生部分成功
        batch(404, "{\"userIds\":[" + someone + ",999999],\"status\":\"BLOCKED\"}");
        Integer auditForSomeone = jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE action = 'user.status.update'"
                        + " AND object_id = ?",
                Integer.class, Long.toString(someone));
        assertThat(auditForSomeone).isZero();
        // 超上限与空集：400
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < 101; i++) {
            ids.append(i + 10000);
            if (i < 100) {
                ids.append(',');
            }
        }
        batch(400, "{\"userIds\":[" + ids + "],\"status\":\"BLOCKED\"}");
        batch(400, "{\"userIds\":[],\"status\":\"BLOCKED\"}");
        batch(400, "{\"userIds\":[" + someone + "],\"status\":\"PAUSED\"}");
        Integer blocked = jdbc.queryForObject(
                "SELECT count(*) FROM sys_user WHERE id = ? AND status = 'BLOCKED'",
                Integer.class, someone);
        assertThat(blocked).isEqualTo(0);
    }

    @Test
    void nonAdminCannotBatch() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/system/users/batch-status")
                        .header("Authorization", userBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\":[2],\"status\":\"BLOCKED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("permission_denied"));
    }
}
