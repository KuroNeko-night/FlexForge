package com.flexforge.auth;

import com.flexforge.common.PublicApi;

import java.time.Instant;

/**
 * 登录防暴破锁定（同一用户名连续失败达阈值，docs/13 §3.1.2）；锁定与解锁均写审计。
 */
@PublicApi
public class AccountLockedException extends RuntimeException {

    private final Instant lockedUntil;

    public AccountLockedException(Instant lockedUntil) {
        super("账号已锁定，请稍后重试");
        this.lockedUntil = lockedUntil;
    }

    public Instant lockedUntil() {
        return lockedUntil;
    }
}
