package com.flexforge.ai.model;

import com.flexforge.common.PublicApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * OpenAI 兼容 HTTP 模型实现（docs/09 P11：接口确认后接入；未配置时默认
 * 不装配——fixture 优先）。密钥仅从环境变量读取，只进 Authorization 头，
 * 不进入日志与任务记录；超时/限流/离线统一转 {@link ModelUnavailableException}。
 */
@PublicApi
@Component
@ConditionalOnProperty(value = "flexforge.ai.provider", havingValue = "http")
public class HttpModelPort implements ModelPort {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http;
    private final String baseUrl;
    private final String model;
    private final String apiKey;

    public HttpModelPort(@Value("${flexforge.ai.base-url:}") String baseUrl,
                         @Value("${flexforge.ai.model:gpt-4o-mini}") String model) {
        this(baseUrl, model, System.getenv("FLEXFORGE_AI_API_KEY"));
    }

    /** 测试与显式装配用构造器（密钥仍不落日志/任务记录）。 */
    HttpModelPort(String baseUrl, String model, String apiKey) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public ModelReply complete(ModelRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ModelUnavailableException(ModelUnavailableException.REASON_OFFLINE,
                    "未配置 FLEXFORGE_AI_API_KEY（在线模型不可用，可走 fixture/手工规格）");
        }
        HttpRequest httpRequest = buildRequest(request);
        try {
            HttpResponse<String> response =
                    http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            requireAcceptable(response);
            return parseReply(response.body());
        } catch (HttpTimeoutException e) {
            throw new ModelUnavailableException(ModelUnavailableException.REASON_TIMEOUT,
                    "模型调用超时（60s），任务未落任何状态，可重试");
        } catch (IOException e) {
            throw new ModelUnavailableException(ModelUnavailableException.REASON_OFFLINE,
                    "模型连接失败: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelUnavailableException(ModelUnavailableException.REASON_OFFLINE,
                    "模型调用被取消");
        }
    }

    private HttpRequest buildRequest(ModelRequest request) {
        var messages = JSON.createArrayNode()
                .add(JSON.createObjectNode().put("role", "user")
                        .put("content", request.prompt()));
        ObjectNode bodyNode = JSON.createObjectNode()
                .put("model", model)
                .put("max_tokens", 4096)
                .put("temperature", 0.2);
        bodyNode.set("messages", messages);
        return HttpRequest.newBuilder(URI.create(baseUrl))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyNode.toString()))
                .build();
    }

    private static void requireAcceptable(HttpResponse<String> response) {
        if (response.statusCode() == 429) {
            throw new ModelUnavailableException(ModelUnavailableException.REASON_RATE_LIMITED,
                    "模型限流（429），任务未落任何状态，可稍后重试");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ModelUnavailableException(ModelUnavailableException.REASON_OFFLINE,
                    "模型接口异常 HTTP " + response.statusCode());
        }
    }

    private static ModelReply parseReply(String responseBody) {
        JsonNode root;
        try {
            root = JSON.readTree(responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            // 200 + 非 JSON 网关页等：归类模型不可用而非 500 兜底
            throw new ModelUnavailableException(ModelUnavailableException.REASON_OFFLINE,
                    "模型响应不是合法 JSON");
        }
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (!content.isTextual()) {
            throw new ModelUnavailableException(ModelUnavailableException.REASON_OFFLINE,
                    "模型响应缺少 choices[0].message.content");
        }
        return new ModelReply(content.asText());
    }

    @Override
    public String name() {
        return "openai-compatible:" + model;
    }
}
