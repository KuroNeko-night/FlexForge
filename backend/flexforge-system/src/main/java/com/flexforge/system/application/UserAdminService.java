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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
        String normalized = normalizeStatus(status);
        if (userId == operatorId) {
            throw new IllegalArgumentException("不能变更自己的账号状态");
        }
        String actor = requireActiveActor(operatorId);
        return applyStatus(actor, userId, normalized);
    }

    /** 批量停启用单请求上限（FR-AUTH-05：误选全量/滥用守卫，docs/09 P24）。 */
    public static final int BATCH_LIMIT = 100;

    /**
     * 批量停启用（FR-AUTH-05，P24）：单事务逐用户变更，逐用户审计（词表与单人
     * 路径一致 user.status.update）。守卫与单人路径同口径：状态白名单/去重/上限
     * 100/不可包含自己/操作者须 ACTIVE/目标须全部存在（未知 id 整批拒绝，不做
     * 部分成功）；同状态用户跳过写入（幂等）但仍进响应与审计。
     */
    @Transactional
    public List<UserAdminRecord> batchUpdateStatus(long operatorId, List<Long> userIds,
                                                   String status) {
        String normalized = normalizeStatus(status);
        List<Long> targets = normalizedBatchTargets(operatorId, userIds);
        String actor = requireActiveActor(operatorId);
        List<UserAdminRecord> updated = new ArrayList<>();
        for (long userId : targets) {
            updated.add(applyStatus(actor, userId, normalized));
        }
        return updated;
    }

    private static String normalizeStatus(String status) {
        return switch (status == null ? "" : status.trim()) {
            case "ACTIVE" -> "ACTIVE";
            case "BLOCKED" -> "BLOCKED";
            default -> throw new IllegalArgumentException("status 仅允许 ACTIVE/BLOCKED");
        };
    }

    /** 批量入参守卫：非空、去重、上限、不可包含操作者自己。 */
    private static List<Long> normalizedBatchTargets(long operatorId, List<Long> userIds) {
        List<Long> distinct = userIds == null
                ? List.of()
                : userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            throw new IllegalArgumentException("userIds 不能为空");
        }
        if (distinct.size() > BATCH_LIMIT) {
            throw new IllegalArgumentException("单次批量上限 " + BATCH_LIMIT + " 个账号");
        }
        if (distinct.contains(operatorId)) {
            throw new IllegalArgumentException("批量目标不能包含自己的账号");
        }
        return distinct;
    }

    /** 操作者须为 ACTIVE（PR #33 审查 P3-15：阻断 BLOCKED 管理员凭存量令牌互停）。 */
    private String requireActiveActor(long operatorId) {
        boolean operatorActive = users.findById(operatorId)
                .map(record -> "ACTIVE".equals(record.status()))
                .orElse(false);
        if (!operatorActive) {
            throw new IllegalArgumentException("操作者账号非启用状态");
        }
        return resolveActor(operatorId);
    }

    /** 单用户状态落库+审计（批量路径复用；同状态跳过写入保持幂等）。 */
    private UserAdminRecord applyStatus(String actor, long userId, String normalized) {
        UserAdminRecord existing = users.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("user not found: " + userId));
        if (!existing.status().equals(normalized)) {
            users.updateStatus(userId, normalized);
        }
        // result 词表对齐全库 success/failure 口径（PR #33 审查 P3-8）；状态值经列表/API 可见
        audit.record(AuditEvents.of(actor, "user.status.update",
                Long.toString(userId), "success", clock));
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
