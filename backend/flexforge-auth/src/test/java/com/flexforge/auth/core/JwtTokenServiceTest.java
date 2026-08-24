package com.flexforge.auth.core;

import com.flexforge.auth.AuthProperties;
import com.flexforge.auth.AuthPrincipal;
import com.flexforge.auth.InvalidTokenException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 签发/校验回归（FR-AUTH-02、docs/13 §3.1.4）：往返、过期、篡改、密钥校验 fail-fast。
 */
class JwtTokenServiceTest {

    /** 测试专用 HMAC 材料（低熵短语+数字，非真实密钥；生产密钥仅 env 注入）。 */
    private static final String HMAC_MATERIAL = "test-hmac-material-0123456789abcdef";

    private JwtTokenService service(String secret, Instant now) {
        AuthProperties properties = new AuthProperties();
        properties.setJwtSecret(secret);
        return new JwtTokenService(properties, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void tokenRoundTripsUserIdAndRoles() {
        Instant now = Instant.parse("2026-08-24T00:00:00Z");
        JwtTokenService service = service(HMAC_MATERIAL, now);

        JwtTokenService.IssuedToken issued = service.issue(42L, List.of("ADMIN", "USER"),
                Duration.ofHours(1));

        assertThat(issued.expiresAt()).isEqualTo(now.plus(Duration.ofHours(1)));
        AuthPrincipal principal = service.parse(issued.token());
        assertThat(principal.userId()).isEqualTo(42L);
        assertThat(principal.roles()).containsExactly("ADMIN", "USER");
        assertThat(principal.hasRole("ADMIN")).isTrue();
    }

    @Test
    void expiredTokenIsRejectedWithDiagnosableMessage() {
        Instant now = Instant.parse("2026-08-24T00:00:00Z");
        JwtTokenService service = service(HMAC_MATERIAL, now);
        String expired = service.issue(1L, List.of(), Duration.ofMinutes(-1)).token();

        assertThatThrownBy(() -> service.parse(expired))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("过期");
    }

    @Test
    void tamperedTokenIsRejected() {
        Instant now = Instant.parse("2026-08-24T00:00:00Z");
        JwtTokenService service = service(HMAC_MATERIAL, now);
        String token = service.issue(1L, List.of("USER"), Duration.ofHours(1)).token();

        String tampered = token.substring(0, token.length() - 2) + "xy";
        assertThatThrownBy(() -> service.parse(tampered))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("无效");
    }

    @Test
    void garbageTokenIsRejected() {
        assertThatThrownBy(() -> service(HMAC_MATERIAL, Instant.now()).parse("not-a-jwt"))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("无效");
    }

    @Test
    void shortSecretFailsFast() {
        AuthProperties properties = new AuthProperties();
        properties.setJwtSecret("too-short");

        assertThatThrownBy(() -> new JwtTokenService(properties, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt-secret");
    }
}
