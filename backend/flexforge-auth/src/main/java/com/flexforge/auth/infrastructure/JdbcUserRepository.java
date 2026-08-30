package com.flexforge.auth.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * sys_user / sys_user_role / sys_role 只读查询与引导写入（auth 模块 infrastructure，
 * 参数化绑定，S1；仅服务于认证内核，用户管理接口在 P03 迭代 2）。
 */
@Repository
public class JdbcUserRepository {

    private final JdbcTemplate jdbc;

    public JdbcUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserRecord> findWithRoles(String username) {
        List<UserRecord> users = jdbc.query(
                "SELECT u.id, u.username, u.password_hash, u.display_name, u.status FROM sys_user u"
                        + " WHERE u.username = ?",
                (rs, i) -> new UserRecord(rs.getLong("id"), rs.getString("username"),
                        rs.getString("password_hash"), rs.getString("display_name"), rs.getString("status"),
                        List.of()),
                username);
        if (users.isEmpty()) {
            return Optional.empty();
        }
        UserRecord user = users.get(0);
        List<String> roles = jdbc.queryForList(
                "SELECT r.code FROM sys_user_role ur JOIN sys_role r ON r.id = ur.role_id"
                        + " WHERE ur.user_id = ? ORDER BY r.code",
                String.class, user.id());
        return Optional.of(new UserRecord(user.id(), user.username(), user.passwordHash(),
                user.displayName(), user.status(), roles));
    }

    public Optional<UserRecord> findById(long id) {
        List<UserRecord> users = jdbc.query(
                "SELECT id, username, password_hash, display_name, status FROM sys_user WHERE id = ?",
                (rs, i) -> new UserRecord(rs.getLong("id"), rs.getString("username"),
                        rs.getString("password_hash"), rs.getString("display_name"), rs.getString("status"),
                        List.of()),
                id);
        if (users.isEmpty()) {
            return Optional.empty();
        }
        UserRecord user = users.get(0);
        List<String> roles = jdbc.queryForList(
                "SELECT r.code FROM sys_user_role ur JOIN sys_role r ON r.id = ur.role_id"
                        + " WHERE ur.user_id = ? ORDER BY r.code",
                String.class, user.id());
        return Optional.of(new UserRecord(user.id(), user.username(), user.passwordHash(),
                user.displayName(), user.status(), roles));
    }

    public long countUsers() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM sys_user", Long.class);
        return count == null ? 0 : count;
    }

    /** 幂等建号（ON CONFLICT DO NOTHING+角色补插）：AdminBootstrap 空库引导与 P13 自助注册共用。 */
    public void insertUserWithRoles(String username, String passwordHash, String displayName,
                                    List<String> roleCodes) {
        jdbc.update("INSERT INTO sys_user (username, password_hash, display_name) VALUES (?, ?, ?)"
                        + " ON CONFLICT (username) DO NOTHING",
                username, passwordHash, displayName);
        Long userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
        for (String roleCode : roleCodes) {
            jdbc.update("INSERT INTO sys_user_role (user_id, role_id)"
                            + " SELECT ?, id FROM sys_role WHERE code = ?"
                            + " AND NOT EXISTS (SELECT 1 FROM sys_user_role WHERE user_id = ? AND role_id ="
                            + " (SELECT id FROM sys_role WHERE code = ?))",
                    userId, roleCode, userId, roleCode);
        }
    }
}
