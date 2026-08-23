package com.flexforge.common;

import java.util.UUID;

/**
 * 对外 requestId 生成器（docs/09 P02 契约冻结）：格式为 {@code req-} + 32 位小写十六进制，
 * 全链路唯一即可，不含语义。日志与统一错误响应均携带该值用于问题定位。
 */
@PublicApi
public final class RequestIds {

    /** requestId 固定前缀（docs/08 §7 示例口径）。 */
    public static final String REQUEST_ID_PREFIX = "req-";

    public static String newRequestId() {
        return REQUEST_ID_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }

    private RequestIds() {
    }
}
