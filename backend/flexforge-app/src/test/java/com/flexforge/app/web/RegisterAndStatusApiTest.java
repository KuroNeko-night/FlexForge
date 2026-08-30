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

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P13 三体验项 API 级验收（docs/09 P13）：自助注册（注册即登录/重名/弱口令/开关/
 * IP 限流 429）与账号停启用（BLOCKED 拒登录/恢复/不可自停/非管理员 403）。
 * 开关与限流阈值经 AuthProperties 动态变更（finally 恢复，共享上下文安全）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RegisterAndStatusApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.flexforge.auth.AuthProperties authProperties;

    private String adminBearer;

    @BeforeEach
    void seedAndLogin() throws Exception {
        AuthTestSupport.seedUsers(jdbc);
        adminBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.ADMIN_USERNAME, AuthTestSupport.adminPassword());
    }

    private String registerBody(String username, String password) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password
                                + "\",\"displayName\":\"自助注册\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.roles[0]").value("USER"))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void registerIssuesTokenAndMeWorks() throws Exception {
        String body = registerBody("selfreg_user", "Selfreg-Pass-1");
        String token = JsonPath.read(body, "$.token");
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("selfreg_user"));
        Integer audit = jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE action = 'auth.register'"
                        + " AND object_id = (SELECT cast(id as varchar) FROM sys_user"
                        + " WHERE username = 'selfreg_user')",
                Integer.class);
        assertThat(audit).isEqualTo(1);
    }

    @Test
    void duplicateAndWeakCredentialsRejected() throws Exception {
        registerBody("dup_user", "Dup-Pass-1234");
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"dup_user\",\"password\":\"Dup-Pass-5678\","
                                + "\"displayName\":\"重复\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"weak_user\",\"password\":\"short\","
                                + "\"displayName\":\"弱口令\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void flagOffRejectsRegisterAndStatusReportsDisabled() throws Exception {
        authProperties.setSelfRegistrationEnabled(false);
        try {
            mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/auth/registration-status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.selfRegistrationEnabled").value(false));
            mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"flagoff_user\",\"password\":\"Flag-Pass-12\","
                                    + "\"displayName\":\"关闭\"}"))
                    .andExpect(status().isBadRequest());
        } finally {
            authProperties.setSelfRegistrationEnabled(true);
        }
    }

    @Test
    void rateLimitReturns429AfterThreshold() throws Exception {
        authProperties.setRegisterRateLimit(1);
        authProperties.setRegisterRateWindow(Duration.ofSeconds(60));
        try {
            registerBody("rate_user_a", "Rate-Pass-12");
            mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"rate_user_b\",\"password\":\"Rate-Pass-34\","
                                    + "\"displayName\":\"限流\"}"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("rate_limited"));
        } finally {
            authProperties.setRegisterRateLimit(5);
            authProperties.setRegisterRateWindow(Duration.ofHours(1));
        }
    }

    @Test
    void statusToggleBlocksLoginAndSelfChangeRejected() throws Exception {
        long demoUserId = userId(AuthTestSupport.USER_USERNAME);
        long adminUserId = userId(AuthTestSupport.ADMIN_USERNAME);

        putStatus(demoUserId, "BLOCKED", adminBearer, 200, "BLOCKED");
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + AuthTestSupport.USER_USERNAME
                                + "\",\"password\":\"" + AuthTestSupport.userPassword() + "\"}"))
                .andExpect(status().isUnauthorized());
        putStatus(demoUserId, "ACTIVE", adminBearer, 200, "ACTIVE");
        putStatus(adminUserId, "BLOCKED", adminBearer, 400, null);
        putStatus(demoUserId, "BLOCKED", "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.DEVELOPER_USERNAME, AuthTestSupport.developerPassword()), 403, null);
        putStatus(demoUserId, "PAUSED", adminBearer, 400, null);
    }

    private long userId(String username) {
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class,
                username);
    }

    private void putStatus(long userId, String status, String bearer, int expected,
                           String expectStatus) throws Exception {
        var result = mockMvc.perform(
                        MockMvcRequestBuilders.put("/api/v1/system/users/" + userId + "/status")
                                .header("Authorization", bearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"" + status + "\"}"))
                .andExpect(status().is(expected));
        if (expectStatus != null) {
            result.andExpect(jsonPath("$.status").value(expectStatus));
        }
    }
}
