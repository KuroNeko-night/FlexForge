package com.flexforge.auth.core;

import com.flexforge.auth.AccountLockedException;
import com.flexforge.auth.AuthProperties;
import com.flexforge.auth.AuthPrincipal;
import com.flexforge.auth.CurrentUser;
import com.flexforge.auth.InvalidCredentialsException;
import com.flexforge.auth.infrastructure.JdbcUserRepository;
import com.flexforge.auth.infrastructure.UserRecord;
import com.flexforge.common.PublicApi;
import com.flexforge.common.audit.AuditEventPort;
import com.flexforge.common.audit.AuditEvents;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * 认证用例（docs/09 P03、docs/13 §3.1）：登录（统一错误防枚举 + 防暴破）、退出（审计事件）、
 * 当前用户资料；关键路径全部写审计（service.audit 端口，P03 落库）。
 *
 * <p>审计口径（P03 迭代 2 统一）：actor=操作者用户名；objectId 登录成功/登出=用户 ID，
 * 登录失败/锁定（用户可能不存在）=用户名字面量；事件统一经 {@link AuditEvents} 工厂构造。
 */
@PublicApi
@Service
public class AuthService {

    public record LoginResult(String token, Instant expiresAt, CurrentUser user) {
    }

    private final JdbcUserRepository users;
    private final AuthKernel kernel;
    private final AuditEventPort audit;
    private final Clock clock;

    public AuthService(JdbcUserRepository users, AuthKernel kernel, AuditEventPort audit, Clock clock) {
        this.users = users;
        this.kernel = kernel;
        this.audit = audit;
        this.clock = clock;
    }

    public LoginResult login(String username, String password) {
        if (kernel.guard().isLocked(username)) {
            throw new AccountLockedException(clock.instant().plus(kernel.properties().getLoginLockDuration()));
        }
        Optional<UserRecord> found = users.findWithRoles(username);
        boolean ok = found.map(user -> user.active()
                        && kernel.hasher().matches(password, user.passwordHash()))
                .orElse(false);
        if (!ok) {
            kernel.guard().onFailure(username)
                    .ifPresent(until -> audit.record(
                            AuditEvents.of(username, "auth.login.locked", username, "success", clock)));
            audit.record(AuditEvents.of(username, "auth.login", username, "failure", clock));
            throw new InvalidCredentialsException();
        }
        UserRecord user = found.orElseThrow();
        kernel.guard().onSuccess(username);
        JwtTokenService.IssuedToken issued =
                kernel.tokens().issue(user.id(), user.roles(), kernel.properties().getJwtTtl());
        audit.record(AuditEvents.of(username, "auth.login", Long.toString(user.id()), "success", clock));
        return new LoginResult(issued.token(), issued.expiresAt(), toCurrentUser(user));
    }

    public CurrentUser currentUser(AuthPrincipal principal) {
        UserRecord user = users.findById(principal.userId())
                .orElseThrow(() -> new NoSuchElementException("user not found: " + principal.userId()));
        if (!user.active()) {
            throw new InvalidCredentialsException();
        }
        return toCurrentUser(user);
    }

    /** 登出 = 审计事件（docs/13 §3.1.5：MVP 不做服务端吊销，前端删除令牌）。 */
    public void logout(AuthPrincipal principal) {
        String actor = users.findById(principal.userId())
                .map(UserRecord::username)
                .orElse("user-" + principal.userId());
        audit.record(AuditEvents.of(actor, "auth.logout", Long.toString(principal.userId()),
                "success", clock));
    }

    private CurrentUser toCurrentUser(UserRecord user) {
        return new CurrentUser(user.id(), user.username(), user.displayName(), user.roles());
    }
}
