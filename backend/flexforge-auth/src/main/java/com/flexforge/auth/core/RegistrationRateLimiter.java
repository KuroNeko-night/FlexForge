package com.flexforge.auth.core;

import com.flexforge.auth.AuthProperties;
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

    /** 尝试获取一次注册配额：窗口内未超限则计数并返回 true，超限返回 false（由调用方审计+拒绝）。 */
    public boolean tryAcquire(String clientIp) {
        Instant now = Instant.now(clock);
        Instant windowStart = now.minus(properties.getRegisterRateWindow());
        Deque<Instant> stamps = attempts.computeIfAbsent(clientIp, k -> new ArrayDeque<>());
        synchronized (stamps) {
            while (!stamps.isEmpty() && stamps.peekFirst().isBefore(windowStart)) {
                stamps.pollFirst();
            }
            if (stamps.size() >= properties.getRegisterRateLimit()) {
                return false;
            }
            stamps.addLast(now);
            return true;
        }
    }
}
