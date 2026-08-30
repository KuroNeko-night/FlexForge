package com.flexforge.ai.model;

import com.flexforge.ai.config.AiConfigService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** 运行时路由：fixture 默认、http 生效配置即时切换（设置页消费面）。 */
class RoutingModelPortTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void routesToFixtureByDefault() {
        AiConfigService configs = Mockito.mock(AiConfigService.class);
        when(configs.effective()).thenReturn(new AiConfigService.EffectiveModelConfig(
                "fixture", "", "m", null));
        RoutingModelPort port = new RoutingModelPort(configs);
        String reply = port.complete(new ModelPort.ModelRequest("v1", "标题"))
                .text();
        assertThat(reply).contains("questions");
        assertThat(port.name()).startsWith("fixture");
    }

    @Test
    void routesToHttpWhenConfiguredAndCarriesKeyHeader() throws IOException {
        StringBuilder authHeader = new StringBuilder();
        startServer(exchange -> {
            authHeader.setLength(0);
            authHeader.append(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        });
        AiConfigService configs = Mockito.mock(AiConfigService.class);
        when(configs.effective()).thenReturn(new AiConfigService.EffectiveModelConfig(
                "http", "http://localhost:" + server.getAddress().getPort(),
                "routing-model", "sk-routing-key"));
        RoutingModelPort port = new RoutingModelPort(configs);
        assertThat(port.complete(new ModelPort.ModelRequest("v1", "p")).text()).isEqualTo("ok");
        assertThat(authHeader.toString()).isEqualTo("Bearer sk-routing-key");
        assertThat(port.name()).isEqualTo("openai-compatible:routing-model");
    }

    private void startServer(RoutingModelPortTest.Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", handler::handle);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status,
                                String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @FunctionalInterface
    interface Handler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }
}
