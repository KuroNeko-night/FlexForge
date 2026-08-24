package com.flexforge.auth.core;

import com.flexforge.auth.AuthProperties;

/**
 * 认证内核组件聚合（参数对象，满足 §7 参数 ≤5）：哈希、令牌、防暴破与配置由同一装配点提供。
 */
public record AuthKernel(PasswordHasher hasher, JwtTokenService tokens, LoginGuard guard,
                         AuthProperties properties) {
}
