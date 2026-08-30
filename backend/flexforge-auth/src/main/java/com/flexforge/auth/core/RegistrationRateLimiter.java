package com.flexforge.auth.core;

import com.flexforge.auth.AuthProperties;
import com.flexforge.auth.RegisterRateLimitedException;
import com.flexforge.common.PublicApi;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自助注册 IP 限流（P13，docs/13 §3.1）：滑动窗口内存口径（单实例演示环境；
 * 不采信 X-Forwarded-For，防伪造头绕过——直连 remoteAddr）。超限抛 429，窗口自动恢复。
 */
@PublicApi
@Component
public class RegistrationRateLimiter {

    private final AuthProperties properties;
    private final Clock clock;
    private final Map<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();

    public RegistrationRateLimiter(AuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** 记录一次注册尝试；窗口内超限即拒绝。 */
    public void check(String clientIp) {
        Instant now = Instant.now(clock);
        Instant windowStart = now.minus(properties.getRegisterRateWindow());
        Deque<Instant> stamps = attempts.computeIfAbsent(clientIp, k -> new ArrayDeque<>());
        synchronized (stamps) {
            while (!stamps.isEmpty() && stamps.peekFirst().isBefore(windowStart)) {
                stamps.pollFirst();
            }
            if (stamps.size() >= properties.getRegisterRateLimit()) {
                throw new RegisterRateLimitedException("注册过于频繁，请稍后再试");
            }
            stamps.addLast(now);
        }
    }
}
