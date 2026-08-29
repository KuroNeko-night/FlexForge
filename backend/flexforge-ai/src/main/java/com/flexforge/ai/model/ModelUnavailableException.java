package com.flexforge.ai.model;

import com.flexforge.common.PublicApi;
import com.flexforge.common.api.ErrorCodes;

/**
 * 模型访问失败（docs/09 P11 验收：超时/取消/限流返回可诊断错误，任务回到
 * 可操作状态）：稳定错误码 model_unavailable，消息不含密钥与完整请求头。
 */
@PublicApi
public class ModelUnavailableException extends RuntimeException {

    public static final String REASON_TIMEOUT = "model_timeout";
    public static final String REASON_RATE_LIMITED = "model_rate_limited";
    public static final String REASON_OFFLINE = "model_offline";

    private final String reason;

    public ModelUnavailableException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }

    public String code() {
        return ErrorCodes.MODEL_UNAVAILABLE;
    }
}
