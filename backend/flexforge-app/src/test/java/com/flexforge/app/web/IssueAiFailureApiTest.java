package com.flexforge.app.web;

import com.flexforge.ai.model.ModelPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RB-AI 失败路径 API 级回归（docs/11：无模型兜底可诊断）：注入可控行为的
 * ModelPort 桩——模型不可用 → 503 model_unavailable（任务不落状态可重试）；
 * 输出持续非法 → 400 model_output_invalid（可走手工规格）。
 * 用例按 @Order 依赖共享桩行为与容器状态。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IssueAiFailureApiTest {

    /** 可控行为桩：默认返回非法 JSON（输出重试超限路径）。 */
    static final AtomicReference<String> BEHAVIOR = new AtomicReference<>("invalid-json");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String developerBearer;

    @TestConfiguration
    static class FailingModelConfig {
        @Bean
        @Primary
        ModelPort failingModelPort() {
            return new ModelPort() {
                @Override
                public ModelReply complete(ModelRequest request) {
                    switch (BEHAVIOR.get()) {
                        case "timeout" -> throw new com.flexforge.ai.model.ModelUnavailableException(
                                com.flexforge.ai.model.ModelUnavailableException.REASON_TIMEOUT,
                                "模型调用超时（60s），任务未落任何状态，可重试");
                        default -> {
                            return new ModelReply("not-json");
                        }
                    }
                }

                @Override
                public String name() {
                    return "failing-stub";
                }
            };
        }
    }

    @BeforeEach
    void seedAndLogin() throws Exception {
        AuthTestSupport.seedUsers(jdbc);
        developerBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.DEVELOPER_USERNAME, AuthTestSupport.developerPassword());
    }

    @Test
    @Order(1)
    void modelTimeoutMapsToServiceUnavailable() throws Exception {
        BEHAVIOR.set("timeout");
        String issueId = createIssue("超时", "模型不可用");
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues/" + issueId + "/clarify")
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("model_unavailable"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("超时")));
    }

    @Test
    @Order(2)
    void persistentInvalidOutputMapsToBadRequestAndManualFallbackWorks() throws Exception {
        BEHAVIOR.set("invalid-json");
        String issueId = createIssue("持续非法输出", "重试超限转手工");
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues/" + issueId + "/clarify")
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("model_output_invalid"));

        // FR-ISSUE-06：模型失败后手工编辑规格继续流程
        mockMvc.perform(MockMvcRequestBuilders.put("/api/v1/issues/" + issueId + "/spec")
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schemaVersion\":1,\"summary\":\"手工\",\"entities\":["
                                + "{\"name\":\"fallback_item\",\"displayName\":\"兜底\","
                                + "\"fields\":[{\"name\":\"name\",\"displayName\":\"名称\","
                                + "\"fieldType\":\"text\"}]}],\"acceptance\":[\"可查询\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }

    private String createIssue(String title, String description) throws Exception {
        String body = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues")
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"description\":\"" + description
                                + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.id");
    }
}
