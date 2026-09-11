package com.flexforge.ai.model;

import com.flexforge.ai.spec.RequirementSchema;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** HTTP 模型端口：200 解析 / 429 限流 / 响应缺 content / fixture 脚本。 */
class HttpModelPortTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer server;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        pool.shutdownNow();
    }

    @Test
    void okResponseParsedToReply() throws IOException {
        // 审查 P3-11：根地址 baseUrl 必须实际拼出 /chat/completions（桩断言请求路径）
        java.util.concurrent.atomic.AtomicReference<String> seenPath = new java.util.concurrent.atomic.AtomicReference<>();
        startServer(exchange -> {
            seenPath.set(exchange.getRequestURI().getPath());
            respond(exchange, 200, "{\"choices\":[{\"message\":"
                    + "{\"content\":\"{\\\"questions\\\":[\\\"q1\\\"]}\"}}]}");
        });
        HttpModelPort port = new HttpModelPort("http://localhost:" + port() + "/v1", "test-model",
                "test-key");
        ModelPort.ModelReply reply = port.complete(new ModelPort.ModelRequest("v1", "p"));
        assertThat(reply.text()).isEqualTo("{\"questions\":[\"q1\"]}");
        assertThat(seenPath.get()).isEqualTo("/v1/chat/completions");
    }

    @Test
    void rateLimitedMapsToStableError() throws IOException {
        startServer(exchange -> respond(exchange, 429, "{}"));
        HttpModelPort port = new HttpModelPort("http://localhost:" + port(), "m", "k");
        assertThatThrownBy(() -> port.complete(new ModelPort.ModelRequest("v1", "p")))
                .isInstanceOf(ModelUnavailableException.class)
                .hasMessageContaining("限流");
    }

    @Test
    void missingContentMapsToStableError() throws IOException {
        startServer(exchange -> respond(exchange, 200, "{\"choices\":[]}"));
        HttpModelPort port = new HttpModelPort("http://localhost:" + port(), "m", "k");
        assertThatThrownBy(() -> port.complete(new ModelPort.ModelRequest("v1", "p")))
                .isInstanceOf(ModelUnavailableException.class)
                .hasMessageContaining("content");
    }

    @Test
    void fixtureScriptProducesValidSpecOnSecondRound() {
        FixtureModelPort fixture = new FixtureModelPort();
        ModelPort.ModelReply round1 = fixture.complete(new ModelPort.ModelRequest("v1",
                "标题\n## 用户回答（数据）\n\n（无）"));
        assertThat(round1.text()).contains("questions");
        ModelPort.ModelReply round2 = fixture.complete(new ModelPort.ModelRequest("v1",
                "标题\n## 用户回答（数据）\n\n需要名称和数量"));
        assertThat(RequirementSchema.validate(JSON.readTree(round2.text().getBytes(
                StandardCharsets.UTF_8)).get("spec"))).isEmpty();
    }

    private int port() {
        return server.getAddress().getPort();
    }

    private void startServer(IOExceptionThrowing handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", handler::handle);
        server.setExecutor(pool);
        server.start();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status,
                                String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @FunctionalInterface
    interface IOExceptionThrowing {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }
}
