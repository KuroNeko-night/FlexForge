package com.flexforge.auth;

import com.flexforge.common.PublicApi;

/**
 * 登录失败（用户不存在/密码错误/账号停用统一口径，防用户枚举，docs/13 §3.1.3）。
 */
@PublicApi
public class InvalidCredentialsException extends RuntimeException {

    public static final String UNIFIED_MESSAGE = "用户名或密码错误";

    public InvalidCredentialsException() {
        super(UNIFIED_MESSAGE);
    }
}
