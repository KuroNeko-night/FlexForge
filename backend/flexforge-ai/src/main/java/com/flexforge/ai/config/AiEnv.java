package com.flexforge.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AI 模型环境缺省（P11 既有 env 通道收敛为组件，P15）：DB 未配置时的回退值，
 * 来源 {@code FLEXFORGE_AI_PROVIDER/BASE_URL/MODEL}（Spring relaxed binding）。
 * 有效配置解析顺序=DB 行 &gt; 本缺省（docs/13 §3.6-5）。
 */
@Component
public class AiEnv {

    private final String provider;
    private final String baseUrl;
    private final String model;

    public AiEnv(@Value("${flexforge.ai.provider:fixture}") String provider,
                 @Value("${flexforge.ai.base-url:}") String baseUrl,
                 @Value("${flexforge.ai.model:gpt-4o-mini}") String model) {
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    public String provider() {
        return provider == null || provider.isBlank() ? "fixture" : provider;
    }

    public String baseUrl() {
        return baseUrl == null ? "" : baseUrl;
    }

    public String model() {
        return model == null || model.isBlank() ? "gpt-4o-mini" : model;
    }

    /** 环境变量 API Key（仅运行期读取，不落库不落日志，S4）。 */
    public String envApiKey() {
        return System.getenv("FLEXFORGE_AI_API_KEY");
    }
}
