package com.flexforge.auth;

import com.flexforge.common.PublicApi;
import com.flexforge.common.api.ErrorCodes;

/** 自助注册触发 IP 限流（P13，docs/13 §3.1）：429 rate_limited，窗口后自动恢复。 */
@PublicApi
public class RegisterRateLimitedException extends RuntimeException {

    public RegisterRateLimitedException(String message) {
        super(message);
    }

    public String code() {
        return ErrorCodes.RATE_LIMITED;
    }
}
