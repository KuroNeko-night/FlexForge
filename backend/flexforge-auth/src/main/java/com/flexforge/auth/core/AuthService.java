package com.flexforge.auth.core;

import com.flexforge.auth.AccountLockedException;
import com.flexforge.auth.AuthProperties;
import com.flexforge.auth.AuthPrincipal;
import com.flexforge.auth.CurrentUser;
import com.flexforge.auth.InvalidCredentialsException;
import com.flexforge.auth.infrastructure.JdbcUserRepository;
import com.flexforge.auth.infrastructure.UserRecord;
import com.flexforge.common.PublicApi;
import com.flexforge.common.audit.AuditEvent;
import com.flexforge.common.audit.AuditEventPort;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * 认证用例（docs/09 P03、docs/13 §3.1）：登录（统一错误防枚举 + 防暴破）、退出（审计事件）、
 * 当前用户资料；关键路径全部写审计（service.audit 端口，P03 落库）。
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
                    .ifPresent(until -> audit.record(event(username, "auth.login.locked", username, "success")));
            audit.record(event(username, "auth.login", username, "failure"));
            throw new InvalidCredentialsException();
        }
        UserRecord user = found.orElseThrow();
        kernel.guard().onSuccess(username);
        JwtTokenService.IssuedToken issued =
                kernel.tokens().issue(user.id(), user.roles(), kernel.properties().getJwtTtl());
        audit.record(event(username, "auth.login", Long.toString(user.id()), "success"));
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
        audit.record(event("user-" + principal.userId(), "auth.logout",
                Long.toString(principal.userId()), "success"));
    }

    private CurrentUser toCurrentUser(UserRecord user) {
        return new CurrentUser(user.id(), user.username(), user.displayName(), user.roles());
    }

    private AuditEvent event(String actor, String action, String objectId, String result) {
        return new AuditEvent("audit-" + UUID.randomUUID(), actor, action, objectId, result,
                clock.instant());
    }
}
