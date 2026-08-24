package com.flexforge.app.web;

import com.flexforge.auth.AuthProperties;
import com.flexforge.auth.core.JwtTokenService;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P03 认证验收（FR-AUTH-01/02、docs/13 §3.1）：登录成功/统一错误/防暴破锁定/JWT 过期与无效/
 * 当前用户/登出审计——全部走真实 HTTP 装配 + 审计落库断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthFlowTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenService tokenService;

    @Autowired
    private AuthProperties authProperties;

    @BeforeEach
    void seed() {
        AuthTestSupport.seedUsers(jdbc);
    }

    private String bearer(String username, String password) {
        return "Bearer " + AuthTestSupport.loginToken(mockMvc, username, password);
    }

    private List<Map<String, Object>> auditRows(String action, String result) {
        return jdbc.queryForList(
                "SELECT actor, action, object_id, result FROM sys_audit_event"
                        + " WHERE action = ? AND result = ? ORDER BY occurred_at DESC",
                action, result);
    }

    @Test
    void loginSuccessReturnsTokenAndAudits() throws Exception {
        String body = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + AuthTestSupport.ADMIN_USERNAME
                                + "\",\"password\":\"" + AuthTestSupport.adminPassword() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.user.username").value(AuthTestSupport.ADMIN_USERNAME))
                .andExpect(jsonPath("$.user.roles[0]").value("ADMIN"))
                .andExpect(header().exists(RequestIdFilter.REQUEST_ID_HEADER))
                .andReturn().getResponse().getContentAsString();

        String token = com.jayway.jsonpath.JsonPath.read(body, "$.token");
        // 令牌不是登录名/口令的函数，且随响应返回（JWT 形态）
        assertThat(token).doesNotContain(AuthTestSupport.ADMIN_USERNAME);

        assertThat(auditRows("auth.login", "success"))
                .anySatisfy(row -> assertThat(row.get("actor")).isEqualTo(AuthTestSupport.ADMIN_USERNAME));
    }

    @Test
    void wrongPasswordAndUnknownUserShareUnifiedMessage() throws Exception {
        for (String username : List.of(AuthTestSupport.ADMIN_USERNAME, "no-such-user")) {
            mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"wrong-pass\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("unauthorized"))
                    .andExpect(jsonPath("$.message").value("用户名或密码错误"))
                    .andExpect(jsonPath("$.requestId").exists());
        }
        assertThat(auditRows("auth.login", "failure").size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void fiveFailuresLockAccountWithAudit() throws Exception {
        String target = "lock-target-user";
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + target + "\",\"password\":\"wrong-pass\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("用户名或密码错误"));
        }

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + target + "\",\"password\":\"wrong-pass\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("锁定")));

        assertThat(auditRows("auth.login.locked", "success"))
                .anySatisfy(row -> assertThat(row.get("actor")).isEqualTo(target));
    }

    @Test
    void protectedApiWithoutTokenIsRejected() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"))
                .andExpect(jsonPath("$.message").value("未提供认证令牌"))
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void meWithValidTokenReturnsCurrentUser() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/auth/me")
                        .header("Authorization", bearer(AuthTestSupport.DEVELOPER_USERNAME,
                                AuthTestSupport.developerPassword())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(AuthTestSupport.DEVELOPER_USERNAME))
                .andExpect(jsonPath("$.displayName").value("测试开发者"))
                .andExpect(jsonPath("$.roles[0]").value("DEVELOPER"));
    }

    @Test
    void expiredTokenIsRejectedWithDiagnosableMessage() throws Exception {
        String expired = tokenService.issue(1L, List.of("USER"),
                java.time.Duration.ofMinutes(-1)).token();

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("登录令牌已过期"));
    }

    @Test
    void malformedTokenIsRejected() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/auth/me")
                        .header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("登录令牌无效"));
    }

    @Test
    void logoutAuditsAndReturnsNoContent() throws Exception {
        long before = auditRows("auth.logout", "success").size();

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/logout")
                        .header("Authorization", bearer(AuthTestSupport.USER_USERNAME,
                                AuthTestSupport.userPassword())))
                .andExpect(status().isNoContent());

        assertThat(auditRows("auth.logout", "success").size()).isEqualTo(before + 1);
    }

    @Test
    void loginMissingFieldIsRejectedAsValidationError() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"someone\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"));
    }

    @Test
    void configuredTtlCoversRequest() {
        // docs/09 P03：TTL 可配置；默认值存在且为正（演示环境覆盖由 .env 配置）
        assertThat(authProperties.getJwtTtl().toMinutes()).isGreaterThan(0);
    }
}
