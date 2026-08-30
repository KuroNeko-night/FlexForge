package com.flexforge.app.web;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P15 设置页 API 级验收（FR-SETUP-01，docs/13 §3.6-5）：读视图只回掩码（明文
 * 零回显）、校验失败可诊断 400、非管理员 403、密钥清除语义、审计行，以及
 * 运行时路由端到端（http 配置→clarify 走本地桩、回退 fixture）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AiConfigApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminBearer;
    private String developerBearer;

    @BeforeEach
    void seedAndLogin() throws Exception {
        AuthTestSupport.seedUsers(jdbc);
        adminBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.ADMIN_USERNAME, AuthTestSupport.adminPassword());
        developerBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.DEVELOPER_USERNAME, AuthTestSupport.developerPassword());
    }

    @AfterEach
    void resetConfigRow() {
        jdbc.update("DELETE FROM ai_provider_config");
    }

    private MockHttpServletRequestBuilder putConfig(String body) {
        return MockMvcRequestBuilders.put("/api/v1/ai/config")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @Test
    void defaultViewIsFixtureWithoutKey() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/ai/config")
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("fixture"))
                .andExpect(jsonPath("$.apiKeyConfigured").value(false));
    }

    @Test
    void updateValidatesProviderAndHttpShape() throws Exception {
        mockMvc.perform(putConfig("{\"provider\":\"openai\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"));
        mockMvc.perform(putConfig("{\"provider\":\"http\",\"model\":\"m\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("base-url")));
        mockMvc.perform(putConfig(
                "{\"provider\":\"http\",\"baseUrl\":\"ftp://x\",\"model\":\"m\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("http://")));
    }

    @Test
    void updateEncryptsKeyAndReadNeverReturnsPlaintext() throws Exception {
        String response = mockMvc.perform(putConfig("{\"provider\":\"http\","
                        + "\"baseUrl\":\"https://api.example/v1\",\"model\":\"demo-model\","
                        + "\"apiKey\":\"sk-live-plaintext-9876\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeyConfigured").value(true))
                .andExpect(jsonPath("$.apiKeyHint").value("…9876"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("sk-live-plaintext-9876");

        String cipher = jdbc.queryForObject(
                "SELECT api_key_cipher FROM ai_provider_config WHERE id = 1", String.class);
        assertThat(cipher).isNotEqualTo("sk-live-plaintext-9876");
        assertThat(cipher).isNotBlank();

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/ai/config")
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeyHint").value("…9876"));

        Integer audit = jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE action = 'ai.config'"
                        + " AND result = 'success'", Integer.class);
        assertThat(audit).isGreaterThanOrEqualTo(1);
    }

    @Test
    void nonAdminIsForbidden() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/ai/config")
                        .header("Authorization", developerBearer))
                .andExpect(status().isForbidden());
        mockMvc.perform(MockMvcRequestBuilders.put("/api/v1/ai/config")
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"fixture\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void clearApiKeyRemovesMaterial() throws Exception {
        mockMvc.perform(putConfig("{\"provider\":\"fixture\",\"apiKey\":\"sk-temp-98765432\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeyConfigured").value(true));
        mockMvc.perform(putConfig("{\"provider\":\"fixture\",\"clearApiKey\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeyConfigured").value(false))
                .andExpect(jsonPath("$.apiKeyHint").doesNotExist());
        String cipher = jdbc.queryForObject(
                "SELECT count(*) FROM ai_provider_config WHERE api_key_cipher IS NOT NULL",
                String.class);
        assertThat(cipher).isEqualTo("0");
    }

    /** 端到端：设置页写入 http+桩地址 → clarify 走桩（模型路由即时生效）→ 回退 fixture。 */
    @Test
    void runtimeRoutingFollowsConfiguredProvider() throws Exception {
        StubModelServer stub = StubModelServer.start();
        try {
            mockMvc.perform(putConfig("{\"provider\":\"http\",\"baseUrl\":\"http://localhost:"
                            + stub.port() + "/v1\",\"model\":\"stub-model\","
                            + "\"apiKey\":\"sk-stub-key\"}"))
                    .andExpect(status().isOk());
            String issueId = createIssueAndClarify();
            String clarifyBody = clarify(issueId, "{}");
            assertThat((String) JsonPath.read(clarifyBody, "$.questions[0]"))
                    .isEqualTo("桩模型追问");
            assertThat(stub.lastAuthorization()).isEqualTo("Bearer sk-stub-key");

            mockMvc.perform(putConfig("{\"provider\":\"fixture\",\"clearApiKey\":true}"))
                    .andExpect(status().isOk());
            assertThat((Boolean) JsonPath.read(clarify(issueId,
                    "{\"answer\":\"名称+数量\"}"), "$.specProduced")).isTrue();
        } finally {
            stub.stop();
        }
    }

    private String clarify(String issueId, String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/issues/" + issueId + "/clarify")
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** OpenAI 兼容桩：记录 Authorization，返回固定追问 JSON。 */
    static final class StubModelServer {
        private final com.sun.net.httpserver.HttpServer server;
        private final StringBuilder auth = new StringBuilder();

        private StubModelServer(com.sun.net.httpserver.HttpServer server) {
            this.server = server;
        }

        static StubModelServer start() throws java.io.IOException {
            StubModelServer holder = new StubModelServer(com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress("localhost", 0), 0));
            holder.server.createContext("/", exchange -> {
                holder.auth.setLength(0);
                holder.auth.append(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] body = ("{\"choices\":[{\"message\":{\"content\":"
                        + "\"{\\\"questions\\\":[\\\"桩模型追问\\\"]}\"}}]}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            holder.server.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor());
            holder.server.start();
            return holder;
        }

        int port() {
            return server.getAddress().getPort();
        }

        String lastAuthorization() {
            return auth.toString();
        }

        void stop() {
            server.stop(0);
        }
    }

    private String createIssueAndClarify() throws Exception {
        String body = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues")
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"设置页路由验证\",\"description\":\"端到端\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }
}
