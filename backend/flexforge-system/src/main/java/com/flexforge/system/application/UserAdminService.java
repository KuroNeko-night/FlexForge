package com.flexforge.system.application;

import com.flexforge.auth.core.PasswordHasher;
import com.flexforge.auth.Roles;
import com.flexforge.common.PublicApi;
import com.flexforge.common.api.PageQuery;
import com.flexforge.common.api.PageResult;
import com.flexforge.common.audit.AuditEventPort;
import com.flexforge.common.audit.AuditEvents;
import com.flexforge.system.infrastructure.JdbcUserAdminRepository;
import com.flexforge.system.infrastructure.UserAdminRecord;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 用户与角色管理用例（FR-AUTH-03，docs/09 P03）：仅管理员（控制器 @RequireRole 声明）；
 * 创建用户、分配角色（权限变化）与分页查询全部写审计。
 * 审计口径（P03 迭代 2 统一）：actor=操作者用户名（经本模块仓储解析，用户不存在回退 user-{id}）。
 */
@PublicApi
@Service
public class UserAdminService {

    public static final Set<String> USER_SORT_FIELDS = Set.of("createdAt", "username");

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z0-9_-]{3,32}$");

    private final JdbcUserAdminRepository users;
    private final PasswordHasher passwordHasher;
    private final AuditEventPort audit;
    private final Clock clock;

    public UserAdminService(JdbcUserAdminRepository users, PasswordHasher passwordHasher,
                            AuditEventPort audit, Clock clock) {
        this.users = users;
        this.passwordHasher = passwordHasher;
        this.audit = audit;
        this.clock = clock;
    }

    /** 创建用户（单事务：用户行+角色绑定原子落库，复审 P1-1；审计失败不阻塞已冻结口径）。 */
    @Transactional
    public UserAdminRecord createUser(long operatorId, String username, String password,
                                      String displayName, List<String> roles) {
        List<String> normalized = validateCreateRequest(username, password, displayName, roles);
        long userId;
        try {
            userId = users.insertUser(username, passwordHasher.hash(password), displayName);
        } catch (DuplicateKeyException e) {
            // 并发重名：唯一约束兜底转可诊断 400（复审 P2-1）
            throw new IllegalArgumentException("用户名已存在: " + username);
        }
        users.replaceRoles(userId, normalized);
        audit.record(AuditEvents.of(resolveActor(operatorId), "user.create", Long.toString(userId),
                "success", clock));
        return users.findById(userId).orElseThrow();
    }

    /** 创建入参校验（S1：API 边界拒绝非法用户名/口令/角色）；通过则返回规范化角色。 */
    private static List<String> validateCreateRequest(String username, String password,
                                                      String displayName, List<String> roles) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new IllegalArgumentException("username 须为 3-32 位小写字母/数字/下划线/连字符");
        }
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw new IllegalArgumentException("password 长度须在 8..128");
        }
        if (displayName == null || displayName.isBlank() || displayName.length() > 64) {
            throw new IllegalArgumentException("displayName 须为 1..64 字符");
        }
        List<String> normalized = normalizeRoles(roles);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("roles 至少包含一个平台角色");
        }
        return normalized;
    }

    public UserAdminRecord assignRoles(long operatorId, long userId, List<String> roles) {
        List<String> normalized = normalizeRoles(roles);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("roles 至少包含一个平台角色");
        }
        UserAdminRecord existing = users.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("user not found: " + userId));
        users.replaceRoles(userId, normalized);
        audit.record(AuditEvents.of(resolveActor(operatorId), "user.roles.update",
                Long.toString(userId), "success", clock));
        return users.findById(userId).orElseThrow();
    }

    public PageResult<UserAdminRecord> listUsers(int page, int pageSize, String sortBy,
                                                 PageQuery.SortDirection direction) {
        PageQuery query = direction == null
                ? PageQuery.of(page, pageSize, sortBy, USER_SORT_FIELDS)
                : PageQuery.of(page, pageSize, sortBy, direction, USER_SORT_FIELDS);
        JdbcUserAdminRepository.UserPage result = users.listUsers(query);
        return new PageResult<>(result.rows(), result.total(), query.pageNumber(), query.pageSize());
    }

    /**
     * 账号停启用（P13，docs/09 P13）：仅 ACTIVE/BLOCKED 两态；不可操作自己
     * （防管理员自锁）。BLOCKED 即拒新登录（login 的 user.active() 既有语义）；
     * 已持有令牌在 TTL 内仍有效（无状态令牌，docs/13 记录）。
     */
    public UserAdminRecord updateStatus(long operatorId, long userId, String status) {
        String normalized = switch (status == null ? "" : status.trim()) {
            case "ACTIVE" -> "ACTIVE";
            case "BLOCKED" -> "BLOCKED";
            default -> throw new IllegalArgumentException("status 仅允许 ACTIVE/BLOCKED");
        };
        if (userId == operatorId) {
            throw new IllegalArgumentException("不能变更自己的账号状态");
        }
        UserAdminRecord existing = users.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("user not found: " + userId));
        if (!existing.status().equals(normalized)) {
            users.updateStatus(userId, normalized);
        }
        audit.record(AuditEvents.of(resolveActor(operatorId), "user.status.update",
                Long.toString(userId), normalized, clock));
        return users.findById(userId).orElseThrow();
    }

    private String resolveActor(long operatorId) {
        return users.findById(operatorId).map(UserAdminRecord::username)
                .orElse("user-" + operatorId);
    }

    private static List<String> normalizeRoles(List<String> roles) {
        if (roles == null) {
            return List.of();
        }
        List<String> distinct = roles.stream().distinct().toList();
        for (String role : distinct) {
            if (!Roles.ALL.contains(role)) {
                throw new IllegalArgumentException("未知角色: " + role);
            }
        }
        return distinct;
    }
}
