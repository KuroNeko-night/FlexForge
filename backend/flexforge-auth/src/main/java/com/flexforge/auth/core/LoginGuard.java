package com.flexforge.auth.core;

import com.flexforge.auth.AuthProperties;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 登录防暴破（docs/13 §3.1.2）：同一用户名连续失败达阈值（默认 5 次）锁定（默认 10 分钟）；
 * 单实例内存实现（MVP 口径），成功登录重置计数。锁定触发由调用方写审计；
 * 锁定到期为被动时间失效（不产生解锁事件），答辩如实陈述。
 */
public final class LoginGuard {

    static final int MAX_TRACKED_USERNAMES = 10_000;

    private record Attempts(int failures, Instant expiresAt) {
    }

    private final int lockAttempts;
    private final java.time.Duration lockDuration;
    private final Clock clock;
    private final Map<String, Attempts> attemptsByUsername = new HashMap<>();

    public LoginGuard(AuthProperties properties, Clock clock) {
        Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lockAttempts = properties.getLoginLockAttempts();
        this.lockDuration = properties.getLoginLockDuration();
    }

    /** 当前是否被锁定（锁定到期自动失效）。 */
    public synchronized boolean isLocked(String username) {
        removeExpired(clock.instant());
        Attempts attempts = attemptsByUsername.get(username);
        if (attempts == null) {
            // Saturation must not evict live counters or unlock a victim (Issue #30).
            return attemptsByUsername.size() >= MAX_TRACKED_USERNAMES;
        }
        return attempts.failures() >= lockAttempts;
    }

    /** 登录失败计数；返回本次失败触发的锁定截止时间（未触发锁定返回 empty）。 */
    public synchronized Optional<Instant> onFailure(String username) {
        Instant now = clock.instant();
        removeExpired(now);
        Attempts attempts = attemptsByUsername.get(username);
        if (attempts != null && attempts.failures() >= lockAttempts) {
            return Optional.of(attempts.expiresAt());
        }
        Instant expiresAt = now.plus(lockDuration);
        if (attempts == null && attemptsByUsername.size() >= MAX_TRACKED_USERNAMES) {
            return Optional.of(expiresAt);
        }
        int failures = attempts == null ? 1 : attempts.failures() + 1;
        attemptsByUsername.put(username, new Attempts(failures, expiresAt));
        return failures >= lockAttempts ? Optional.of(expiresAt) : Optional.empty();
    }

    /** 登录成功重置；若此前存在失败计数/锁定记录返回 true。 */
    public synchronized boolean onSuccess(String username) {
        removeExpired(clock.instant());
        return attemptsByUsername.remove(username) != null;
    }

    private void removeExpired(Instant now) {
        attemptsByUsername.values().removeIf(attempts -> !attempts.expiresAt().isAfter(now));
    }
}
