package com.flexforge.app.web;

import com.flexforge.auth.AuthProperties;
import com.flexforge.auth.core.AuthKernel;
import com.flexforge.auth.core.AuthService;
import com.flexforge.auth.core.JwtTokenService;
import com.flexforge.auth.core.LoginGuard;
import com.flexforge.auth.core.PasswordHasher;
import com.flexforge.auth.core.RegistrationRateLimiter;
import com.flexforge.auth.infrastructure.JdbcUserRepository;
import com.flexforge.auth.infrastructure.UserRecord;
import com.flexforge.common.audit.AuditEvent;
import com.flexforge.common.audit.AuditEventPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 注册通道账号接管防线（PR #33 审查 P2-2）：并发窗口下"查重通过→他人先插入同名
 * 账号→幂等插入被 DO NOTHING 吞→查回他人行"——插入后哈希复核必须拒绝且绝不
 * 签发令牌（API 层难构造并发，此处锁定该分支的单元回归）。
 */
class AuthServiceTakeoverGuardTest {

    private static final String TEST_SECRET = "test-only-secret-0123456789abcdef0123456789abcdef";

    /** 组装被测服务：仓储/令牌/审计 mock，哈希与限流用真实实现。 */
    private AuthService authService(JdbcUserRepository users, JwtTokenService tokens,
                                    AuditEventPort audit, AuthProperties properties,
                                    Clock clock) {
        AuthKernel kernel = new AuthKernel(new PasswordHasher(), tokens,
                new LoginGuard(properties, clock), properties);
        return new AuthService(users, kernel, audit, clock,
                new RegistrationRateLimiter(properties, clock));
    }

    @Test
    void takeoverAttemptViaSwallowedInsertIsRejectedWithoutToken() {
        PasswordHasher hasher = new PasswordHasher();
        JdbcUserRepository users = mock(JdbcUserRepository.class);
        JwtTokenService tokens = mock(JwtTokenService.class);
        AuditEventPort audit = mock(AuditEventPort.class);
        Clock clock = Clock.systemUTC();
        AuthService service = authService(users, tokens, audit, new AuthProperties(), clock);

        // 并发时序：查重时不存在 → 他人抢先建号（受害者口令的哈希）→ 本次插入被吞
        UserRecord victim = new UserRecord(42L, "victim", hasher.hash("Victim-Pass-123"),
                "受害者", "ACTIVE", List.of("USER"));
        when(users.findWithRoles("victim")).thenReturn(Optional.empty()).thenReturn(Optional.of(victim));

        assertThatThrownBy(() -> service.register("victim", "Attacker-Pass-9", "攻击者", "9.9.9.9"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户名已存在");

        // 绝不签发令牌（接管未遂），且失败路径写审计（PR #33 审查 P2-1 口径）
        verify(tokens, never()).issue(anyLong(), any(), any());
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("auth.register");
        assertThat(captor.getValue().result()).isEqualTo("failure");
    }

    @Test
    void normalRegistrationStillIssuesToken() {
        PasswordHasher hasher = new PasswordHasher();
        JdbcUserRepository clean = mock(JdbcUserRepository.class);
        when(clean.findWithRoles("fresh")).thenReturn(Optional.empty())
                .thenReturn(Optional.of(new UserRecord(43L, "fresh", hasher.hash("Fresh-Pass-1"),
                        "新用户", "ACTIVE", List.of("USER"))));
        JwtTokenService okTokens = mock(JwtTokenService.class);
        Clock clock = Clock.systemUTC();
        when(okTokens.issue(anyLong(), any(), any())).thenReturn(
                new JwtTokenService.IssuedToken("tk", clock.instant().plusSeconds(60)));
        AuthProperties properties = new AuthProperties();
        properties.setJwtSecret(TEST_SECRET);
        properties.setJwtTtl(Duration.ofHours(1));
        AuthService okService = authService(clean, okTokens, mock(AuditEventPort.class),
                properties, clock);

        // 正向对照：无并发时注册成功签发令牌（防线不误伤）
        assertThat(okService.register("fresh", "Fresh-Pass-1", "新用户", "8.8.8.8")
                .token()).isEqualTo("tk");
        verify(okTokens).issue(anyLong(), any(), any());
    }
}
