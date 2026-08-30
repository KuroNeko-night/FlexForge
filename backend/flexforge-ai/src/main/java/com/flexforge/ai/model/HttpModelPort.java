package com.flexforge.ai.model;

import com.flexforge.ai.config.AiConfigService;
import com.flexforge.common.PublicApi;
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
 * OpenAI 兼容 HTTP 模型实现（docs/09 P11；P15 起每次调用读取运行时有效配置
 * ——DB 行 &gt; 环境变量，密钥来自设置页密文或 FLEXFORGE_AI_API_KEY）。密钥只进
 * Authorization 头，不进入日志与任务记录；超时/限流/离线统一转
 * {@link ModelUnavailableException}。不注册为 Bean（由 RoutingModelPort 持有）。
 */
@PublicApi
public class HttpModelPort implements ModelPort {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http;
    private final AiConfigService configs;
    /** 测试直连配置（绕过运行时解析；生产装配为 null）。 */
    private final AiConfigService.EffectiveModelConfig fixed;

    public HttpModelPort(AiConfigService configs) {
        this(configs, null);
    }

    /** 测试与显式装配用构造器（密钥仍不落日志/任务记录）。 */
    HttpModelPort(String baseUrl, String model, String apiKey) {
        this(null, new AiConfigService.EffectiveModelConfig("http", baseUrl, model, apiKey));
    }

    private HttpModelPort(AiConfigService configs,
                          AiConfigService.EffectiveModelConfig fixed) {
        this.configs = configs;
        this.fixed = fixed;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** 单轮补全；所有网络层失败（429/超时/离线/非 JSON 网关页）统一转
     * {@link ModelUnavailableException}（HTTP 503 口径见 GlobalExceptionHandler）。
     * 本方法不落任何业务状态：失败发生在编排层写规格/迁移之前，调用方可安全重试，
     * 不会留下半成品任务。 */
    @Override
    public ModelReply complete(ModelRequest request) {
        AiConfigService.EffectiveModelConfig config =
                fixed != null ? fixed : configs.effective();
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new ModelUnavailableException(ModelUnavailableException.REASON_OFFLINE,
                    "未配置模型 API Key（设置页或 FLEXFORGE_AI_API_KEY；离线可走 fixture/手工规格）");
        }
        HttpRequest httpRequest = buildRequest(request, config);
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

    private HttpRequest buildRequest(ModelRequest request,
                                     AiConfigService.EffectiveModelConfig config) {
        var messages = JSON.createArrayNode()
                .add(JSON.createObjectNode().put("role", "user")
                        .put("content", request.prompt()));
        ObjectNode bodyNode = JSON.createObjectNode()
                .put("model", config.model())
                // max_tokens 封顶防超长回复撑爆解析；低温度让规格输出偏确定性，
                // 输出偏差交由 ClarifyEngine 的校验反馈重试纠正
                .put("max_tokens", 4096)
                .put("temperature", 0.2);
        bodyNode.set("messages", messages);
        try {
            return HttpRequest.newBuilder(URI.create(config.baseUrl()))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(bodyNode.toString()))
                    .build();
        } catch (IllegalArgumentException e) {
            // 配置层漏网的非法 base-url 在此兜底归类为模型不可用，不冒泡 500
            throw new ModelUnavailableException(ModelUnavailableException.REASON_OFFLINE,
                    "base-url 非法，无法发起模型请求");
        }
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
        AiConfigService.EffectiveModelConfig config =
                fixed != null ? fixed : configs.effective();
        return "openai-compatible:" + config.model();
    }
}
