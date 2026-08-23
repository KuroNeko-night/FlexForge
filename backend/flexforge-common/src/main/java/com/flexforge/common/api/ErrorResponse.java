package com.flexforge.common.api;

import com.flexforge.common.PublicApi;

import java.util.Objects;

/**
 * 统一错误响应体（docs/09 P02 契约冻结）：任一 API 错误都返回稳定错误码、消息与 requestId。
 *
 * <p>code 取值见 {@link ErrorCodes}；requestId 全链路透传，便于日志关联。
 */
@PublicApi
public record ErrorResponse(String code, String message, String requestId) {

    public ErrorResponse {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(requestId, "requestId");
    }

    public static ErrorResponse of(String code, String message, String requestId) {
        return new ErrorResponse(code, message, requestId);
    }
}
