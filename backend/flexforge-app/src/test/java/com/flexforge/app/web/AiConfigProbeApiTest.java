package com.flexforge.app.web;

import org.junit.jupiter.api.AfterEach;
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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P26 保存探活 API 级验收（FR-SETUP-01，docs/13 §3.6-6）：真实守卫/探活闸下
 * 的失败路径——环回/私有地址 400、无密钥 400、配置不落库、审计 failure 行。
 * 全部走守卫静态判定，无网络依赖；探活半程语义见 HttpModelConfigGateTest。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AiConfigProbeApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminBearer;

    @BeforeEach
    void seedAndLogin() throws Exception {
        AuthTestSupport.seedUsers(jdbc);
        adminBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.ADMIN_USERNAME, AuthTestSupport.adminPassword());
    }

    @AfterEach
    void resetConfigRow() {
        jdbc.update("DELETE FROM ai_provider_config");
        jdbc.update("DELETE FROM sys_audit_event WHERE action = 'ai.config'");
    }

    private org.springframework.test.web.servlet.ResultActions putHttp(String baseUrl)
            throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.put("/api/v1/ai/config")
                        .header("Authorization", adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"http\",\"baseUrl\":\"" + baseUrl
                                + "\",\"model\":\"deepseek-flash\","
                                + "\"apiKey\":\"sk-probe-987654\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"));
    }

    @Test
    void loopbackBaseUrlRejectedBeforeProbe() throws Exception {
        putHttp("http://127.0.0.1:9").andExpect(
                jsonPath("$.message").value(containsString("环回")));
        assertNothingPersisted();
    }

    @Test
    void privateNetworkBaseUrlRejected() throws Exception {
        putHttp("http://192.168.0.1:9").andExpect(
                jsonPath("$.message").value(containsString("私有")));
        assertNothingPersisted();
    }

    @Test
    void missingApiKeyRejected() throws Exception {
        // 无密钥=形态校验拒绝（与 shape 400 同类不审计），只断言 400 与不落库
        mockMvc.perform(MockMvcRequestBuilders.put("/api/v1/ai/config")
                        .header("Authorization", adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"http\","
                                + "\"baseUrl\":\"https://api.deepseek.com\","
                                + "\"model\":\"deepseek-flash\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("API Key")));
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM ai_provider_config", Integer.class);
        org.assertj.core.api.Assertions.assertThat(rows).isZero();
    }

    @Test
    void fixtureSaveBypassesProbeAndSucceeds() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.put("/api/v1/ai/config")
                        .header("Authorization", adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"fixture\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("fixture"));
    }

    private void assertNothingPersisted() {
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM ai_provider_config", Integer.class);
        org.assertj.core.api.Assertions.assertThat(rows).isZero();
        Integer failures = jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE action = 'ai.config'"
                        + " AND result = 'failure'", Integer.class);
        org.assertj.core.api.Assertions.assertThat(failures).isGreaterThanOrEqualTo(1);
    }
}
