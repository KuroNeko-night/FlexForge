package com.flexforge.auth.core;

import com.flexforge.auth.AuthPrincipal;
import com.flexforge.auth.AuthProperties;
import com.flexforge.auth.InvalidTokenException;
import com.flexforge.common.PublicApi;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * JWT HS256 签发与校验（docs/13 §3.1.4）：payload 只放用户 ID、角色与时间；
 * 密钥 >= 256bit 且仅经环境注入，构造时 fail-fast；过期/无效给出可诊断细分消息。
 */
@PublicApi
public final class JwtTokenService {

    /** 最小密钥长度（字节）：HS256 要求 >= 256bit。 */
    private static final int MIN_SECRET_BYTES = 32;

    private final JWSSigner signer;
    private final JWSVerifier verifier;
    private final Clock clock;

    public JwtTokenService(AuthProperties properties, Clock clock) {
        Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        byte[] secretBytes = properties.getJwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("flexforge.auth.jwt-secret 缺失或不足 " + MIN_SECRET_BYTES
                    + " 字节（HS256 要求 >=256bit，仅 env 注入，docs/13 S4）");
        }
        try {
            this.signer = new MACSigner(secretBytes);
            this.verifier = new MACVerifier(secretBytes);
        } catch (Exception e) {
            throw new IllegalStateException("JWT 签名器初始化失败", e);
        }
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }

    /** 为用户按显式 TTL 签发（TTL 由调用方从配置传入，演示环境可覆盖完整演示时长）。 */
    public IssuedToken issue(long userId, List<String> roles, Duration ttl) {
        Objects.requireNonNull(roles, "roles");
        Objects.requireNonNull(ttl, "ttl");
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl);
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(Long.toString(userId))
                    .claim("roles", roles)
                    .issueTime(java.util.Date.from(now))
                    .expirationTime(java.util.Date.from(expiresAt))
                    .build();
            SignedJWT signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            signed.sign(signer);
            return new IssuedToken(signed.serialize(), expiresAt);
        } catch (Exception e) {
            throw new IllegalStateException("JWT 签发失败", e);
        }
    }

    /** 校验签名与有效期并还原认证主体；无效/过期分别抛出可诊断异常。 */
    public AuthPrincipal parse(String token) {
        Objects.requireNonNull(token, "token");
        SignedJWT signed;
        try {
            signed = SignedJWT.parse(token);
        } catch (Exception e) {
            throw InvalidTokenException.malformed();
        }
        boolean verified;
        try {
            verified = signed.verify(verifier);
        } catch (Exception e) {
            throw InvalidTokenException.malformed();
        }
        if (!verified) {
            throw InvalidTokenException.malformed();
        }
        JWTClaimsSet claims;
        try {
            claims = signed.getJWTClaimsSet();
        } catch (Exception e) {
            throw InvalidTokenException.malformed();
        }
        Instant expiresAt = claims.getExpirationTime() == null ? null : claims.getExpirationTime().toInstant();
        if (expiresAt == null || !expiresAt.isAfter(clock.instant())) {
            throw InvalidTokenException.expired();
        }
        return new AuthPrincipal(parseUserId(claims), parseRoles(claims));
    }

    private static long parseUserId(JWTClaimsSet claims) {
        try {
            return Long.parseLong(claims.getSubject());
        } catch (NumberFormatException e) {
            throw InvalidTokenException.malformed();
        }
    }

    private static List<String> parseRoles(JWTClaimsSet claims) {
        try {
            return claims.getStringListClaim("roles");
        } catch (java.text.ParseException e) {
            throw InvalidTokenException.malformed();
        }
    }

    @Override
    public String toString() {
        return "JwtTokenService"; // S4/S8：密钥不得出现在任何输出中
    }
}
