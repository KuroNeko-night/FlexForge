package com.flexforge.auth.core;

import com.flexforge.auth.AccountLockedException;
import com.flexforge.auth.AuthProperties;
import com.flexforge.auth.AuthPrincipal;
import com.flexforge.auth.CurrentUser;
import com.flexforge.auth.InvalidCredentialsException;
import com.flexforge.auth.Roles;
import com.flexforge.auth.infrastructure.JdbcUserRepository;
import com.flexforge.auth.infrastructure.UserRecord;
import com.flexforge.common.PublicApi;
import com.flexforge.common.audit.AuditEventPort;
import com.flexforge.common.audit.AuditEvents;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
    private final RegistrationRateLimiter rateLimiter;

    public AuthService(JdbcUserRepository users, AuthKernel kernel, AuditEventPort audit,
                       Clock clock, RegistrationRateLimiter rateLimiter) {
        this.users = users;
        this.kernel = kernel;
        this.audit = audit;
        this.clock = clock;
        this.rateLimiter = rateLimiter;
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
            // 停用账号沿用登录统一口径（docs/13 §3.1.3）：防止持有有效旧令牌者借 /me 探测账号当前状态
            throw new InvalidCredentialsException();
        }
        return toCurrentUser(user);
    }

    /** 注册入口可见性（P13 feature flag：控制器匿名端点消费）。 */
    public boolean isSelfRegistrationEnabled() {
        return kernel.properties().isSelfRegistrationEnabled();
    }

    /** 登出 = 审计事件（docs/13 §3.1.5：MVP 不做服务端吊销，前端删除令牌）。 */
    public void logout(AuthPrincipal principal) {
        // 令牌 TTL 内用户可能已被删除（无服务端吊销）：actor 回退字面量保证登出审计不因取不到用户名而丢失
        String actor = users.findById(principal.userId())
                .map(UserRecord::username)
                .orElse("user-" + principal.userId());
        audit.record(AuditEvents.of(actor, "auth.logout", Long.toString(principal.userId()),
                "success", clock));
    }

    /** 自助注册用户名规则（与 UserAdminService.USERNAME_PATTERN 同款；P13 注册边界）。 */
    private static final java.util.regex.Pattern REGISTER_USERNAME =
            java.util.regex.Pattern.compile("^[a-z0-9_-]{3,32}$");

    /**
     * 自助注册（P13，docs/09 P13）：开关 + IP 限流 + 同款口令/用户名策略；
     * 默认 USER 角色；成功即签发令牌（等价登录，避免二次明文提交）。审计 auth.register：
     * success=建号成功；failure=限流触发或并发重名接管阻断（PR #33 审查 P2-1：
     * 安全关键失败路径必须可追溯，对齐 login 全路径审计口径）。
     */
    public LoginResult register(String username, String password, String displayName,
                                String clientIp) {
        if (!kernel.properties().isSelfRegistrationEnabled()) {
            throw new IllegalArgumentException("自助注册未开放");
        }
        if (!rateLimiter.tryAcquire(clientIp)) {
            audit.record(AuditEvents.of("ip:" + clientIp, "auth.register", username,
                    "failure", clock));
            throw new com.flexforge.auth.RegisterRateLimitedException("注册过于频繁，请稍后再试");
        }
        requireRegistrationInput(username, password, displayName);
        if (username.equals(kernel.properties().getBootstrapAdminUsername())) {
            // PR #33 审查 P2-3：保留名防线——防止空库窗口抢注引导管理员用户名
            // 毒化 AdminBootstrap（countUsers>0 后永不建管理员且无 API 恢复路径）
            throw new IllegalArgumentException("该用户名已保留");
        }
        if (users.findWithRoles(username).isPresent()) {
            throw new IllegalArgumentException("用户名已存在: " + username);
        }
        try {
            users.insertUserWithRoles(username, kernel.hasher().hash(password), displayName,
                    List.of(Roles.USER));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 防御纵深：幂等插入理论上不触发，保留以防仓储语义回退
            throw new IllegalArgumentException("用户名已存在: " + username);
        }
        UserRecord created = users.findWithRoles(username).orElseThrow();
        if (!kernel.hasher().matches(password, created.passwordHash())) {
            // 并发重名兜底：insertUserWithRoles 幂等（ON CONFLICT DO NOTHING），若他人
            // 先建同名账号则本次插入被吞——哈希不匹配即非本次创建，绝不给既有账号发令牌
            audit.record(AuditEvents.of(username, "auth.register", username, "failure", clock));
            throw new IllegalArgumentException("用户名已存在: " + username);
        }
        audit.record(AuditEvents.of(username, "auth.register", Long.toString(created.id()),
                "success", clock));
        JwtTokenService.IssuedToken issued = kernel.tokens().issue(created.id(), created.roles(),
                kernel.properties().getJwtTtl());
        return new LoginResult(issued.token(), issued.expiresAt(), toCurrentUser(created));
    }

    /** 注册入参校验（与 UserAdminService.createUser 同规则；S1 API 边界拒绝）。 */
    private static void requireRegistrationInput(String username, String password,
                                                 String displayName) {
        if (username == null || !REGISTER_USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException("username 须为 3-32 位小写字母/数字/下划线/连字符");
        }
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw new IllegalArgumentException("password 长度须在 8..128");
        }
        if (displayName == null || displayName.isBlank() || displayName.length() > 64) {
            throw new IllegalArgumentException("displayName 须为 1..64 字符");
        }
    }

    private CurrentUser toCurrentUser(UserRecord user) {
        return new CurrentUser(user.id(), user.username(), user.displayName(), user.roles());
    }
}
