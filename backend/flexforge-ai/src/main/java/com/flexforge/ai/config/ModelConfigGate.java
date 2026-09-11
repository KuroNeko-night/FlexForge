package com.flexforge.ai.config;

import com.flexforge.common.PublicApi;

/**
 * AI 配置保存前校验闸（P26，FR-SETUP-01）：provider=http 保存时校验出站 URL
 * （{@link ModelUrlGuard}）并探活上游（GET {base}/models 带 Bearer）。不通过抛
 * IllegalArgumentException（映射 400，报错上抛）；密钥明文只进 Authorization 头，
 * 不进日志/审计/异常消息（S8）。
 */
@PublicApi
public interface ModelConfigGate {

    /**
     * 保存前校验+探活。前置条件：provider=http 且 baseUrl/model 已过形态校验，
     * apiKey 为本次生效明文（新录入或存量解密）。
     */
    void checkOnSave(String baseUrl, String model, String apiKey);
}
