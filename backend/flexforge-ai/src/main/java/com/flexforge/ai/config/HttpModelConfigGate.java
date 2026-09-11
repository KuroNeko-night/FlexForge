package com.flexforge.ai.config;

import com.flexforge.ai.model.ModelEndpoints;
import com.flexforge.common.PublicApi;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 保存前探活实现（P26，FR-SETUP-01）：{@link ModelUrlGuard} 守卫后 GET
 * {base}/models（OpenAI/DeepSeek 标准）验证可达性与密钥；模型列表可解析时校验
 * model 在列（不在列给出上游可用模型提示）。2xx 但列表不可解析视为可达（兼容
 * 非标准 /models 实现）。密钥只进 Authorization 头；异常消息只含状态码/原因。
 */
@PublicApi
@Service
public class HttpModelConfigGate implements ModelConfigGate {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http;
    private final Duration timeout;

    public HttpModelConfigGate() {
        this(Duration.ofSeconds(10));
    }

    /** 单测用超时注入（默认 10s）。 */
    HttpModelConfigGate(Duration timeout) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.timeout = timeout;
    }

    @Override
    public void checkOnSave(String baseUrl, String model, String apiKey) {
        ModelUrlGuard.requireFetchable(baseUrl);
        probeUpstream(baseUrl, model, apiKey);
    }

    /** 探活半程（包内可见供单测；前置条件=URL 已过守卫）。 */
    void probeUpstream(String baseUrl, String model, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("未配置模型 API Key，无法探活上游");
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(ModelEndpoints.modelsOf(baseUrl)))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("base-url 非法，无法发起探活");
        }
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new IllegalArgumentException("上游探活超时（10s），请检查 base-url 可达性");
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "上游不可达: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("上游探活被取消");
        }
        requireProbeAcceptable(response.statusCode());
        requireModelListed(response.body(), model);
    }

    private static void requireProbeAcceptable(int status) {
        if (status == 401 || status == 403) {
            throw new IllegalArgumentException("上游密钥无效（HTTP " + status + "）");
        }
        if (status == 402) {
            throw new IllegalArgumentException("上游账户余额不足（HTTP 402）");
        }
        if (status == 429) {
            throw new IllegalArgumentException("上游限流（HTTP 429），请稍后重试");
        }
        if (status < 200 || status >= 300) {
            throw new IllegalArgumentException("上游接口异常 HTTP " + status);
        }
    }

    /** 列表可解析且非空时校验 model 在列；解析失败/空列表=可达即通过（非标准兼容）。 */
    private static void requireModelListed(String responseBody, String model) {
        JsonNode root;
        try {
            root = JSON.readTree(responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            if (item.path("id").isTextual()) {
                ids.add(item.path("id").asText());
            }
        }
        if (ids.isEmpty() || ids.contains(model)) {
            return;
        }
        String hint = String.join(", ", ids.subList(0, Math.min(3, ids.size())));
        throw new IllegalArgumentException(
                "上游模型列表不含 " + model + "（可用示例: " + hint + "）");
    }
}
