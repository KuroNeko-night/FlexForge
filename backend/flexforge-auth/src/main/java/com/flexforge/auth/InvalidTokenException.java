package com.flexforge.auth;

import com.flexforge.common.PublicApi;

/**
 * JWT 校验失败（缺失/无效/过期细分消息，docs/09 P03 验收：可诊断错误）。
 */
@PublicApi
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message) {
        super(message);
    }

    public static InvalidTokenException missing() {
        return new InvalidTokenException("未提供认证令牌");
    }

    public static InvalidTokenException malformed() {
        return new InvalidTokenException("登录令牌无效");
    }

    public static InvalidTokenException expired() {
        return new InvalidTokenException("登录令牌已过期");
    }
}
