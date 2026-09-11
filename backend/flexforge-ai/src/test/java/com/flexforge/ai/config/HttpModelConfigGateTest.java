package com.flexforge.ai.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 保存前探活（P26，FR-SETUP-01）：GET {base}/models 的可达/密钥/模型在列语义。
 * probeUpstream 前置=URL 已过守卫（单测用本地桩直接探活半程）；checkOnSave 验证
 * 守卫先行（本地地址在组合层被拒）。
 */
class HttpModelConfigGateTest {

    private com.sun.net.httpserver.HttpServer server;
    private final Semaphore hang = new Semaphore(1);

    @BeforeEach
    void start() throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void respond(Consumer<com.sun.net.httpserver.HttpExchange> handler) {
        server.createContext("/", exchange -> handler.accept(exchange));
    }

    private static void send(com.sun.net.httpserver.HttpExchange exchange, int status, String body) {
        try (exchange) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void probePassesWhenModelListed() {
        respond(ex -> send(ex, 200,
                "{\"data\":[{\"id\":\"deepseek-flash\"},{\"id\":\"deepseek-v4-pro\"}]}"));
        HttpModelConfigGate gate = new HttpModelConfigGate(Duration.ofSeconds(5));
        assertThatCode(() -> gate.probeUpstream(
                "http://localhost:" + server.getAddress().getPort(), "deepseek-flash", "sk-x"))
                .doesNotThrowAnyException();
    }

    @Test
    void probeRejectsWhenModelMissingFromList() {
        respond(ex -> send(ex, 200, "{\"data\":[{\"id\":\"deepseek-flash\"}]}"));
        HttpModelConfigGate gate = new HttpModelConfigGate(Duration.ofSeconds(5));
        assertThatThrownBy(() -> gate.probeUpstream(
                "http://localhost:" + server.getAddress().getPort(), "nope-model", "sk-x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope-model")
                .hasMessageContaining("deepseek-flash");
    }

    @Test
    void probeTreatsUnparsableListAsReachable() {
        respond(ex -> send(ex, 200, "<html>gateway</html>"));
        HttpModelConfigGate gate = new HttpModelConfigGate(Duration.ofSeconds(5));
        assertThatCode(() -> gate.probeUpstream(
                "http://localhost:" + server.getAddress().getPort(), "any-model", "sk-x"))
                .doesNotThrowAnyException();
    }

    @Test
    void probeMapsUpstreamErrorsToReasons() {
        respond(ex -> send(ex, 401, "{\"error\":\"Authentication Failure\"}"));
        HttpModelConfigGate gate = new HttpModelConfigGate(Duration.ofSeconds(5));
        assertThatThrownBy(() -> gate.probeUpstream(
                "http://localhost:" + server.getAddress().getPort(), "m", "bad-key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("密钥无效")
                .hasMessageContaining("401");
    }

    @Test
    void probeRejectsBlankKeyBeforeNetwork() {
        HttpModelConfigGate gate = new HttpModelConfigGate(Duration.ofSeconds(5));
        assertThatThrownBy(() -> gate.probeUpstream(
                "http://localhost:" + server.getAddress().getPort(), "m", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("API Key");
    }

    @Test
    void probeTimesOutOnHangingUpstream() throws Exception {
        hang.acquire();
        respond(ex -> {
            try {
                hang.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            send(ex, 200, "{}");
        });
        HttpModelConfigGate gate = new HttpModelConfigGate(Duration.ofMillis(300));
        try {
            assertThatThrownBy(() -> gate.probeUpstream(
                    "http://localhost:" + server.getAddress().getPort(), "m", "sk-x"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("超时");
        } finally {
            hang.release();
        }
    }

    @Test
    void checkOnSaveRejectsLoopbackBeforeProbe() {
        HttpModelConfigGate gate = new HttpModelConfigGate(Duration.ofSeconds(5));
        assertThatThrownBy(() -> gate.checkOnSave(
                "http://localhost:" + server.getAddress().getPort(), "m", "sk-x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("localhost");
    }
}
