package com.flexforge.system.infrastructure;

import com.flexforge.common.api.PageQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 用户/角色管理仓储（system 模块 infrastructure）：分页查询 + 创建 + 角色全量替换。
 * 排序白名单由 Service 层经 PageQuery 强制，此处映射为固定列名（S1：无动态 SQL 拼接面）。
 */
@Repository
public class JdbcUserAdminRepository {

    private final JdbcTemplate jdbc;

    public JdbcUserAdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record UserPage(List<UserAdminRecord> rows, long total) {
    }

    public UserPage listUsers(PageQuery query) {
        // 排序白名单（Service 层强制 {createdAt, username}），此处仅映射为固定列名（S1）
        String orderColumn = "username".equals(query.sortBy()) ? "u.username" : "u.created_at";
        String direction = query.sortDirection() == com.flexforge.common.api.PageQuery.SortDirection.DESC
                ? "DESC" : "ASC";
        long total = Optional.ofNullable(
                jdbc.queryForObject("SELECT count(*) FROM sys_user u", Long.class)).orElse(0L);
        int offset = (query.pageNumber() - 1) * query.pageSize();
        List<Long> ids = jdbc.queryForList(
                "SELECT u.id FROM sys_user u ORDER BY " + orderColumn + " " + direction
                        + ", u.id ASC LIMIT ? OFFSET ?",
                Long.class, query.pageSize(), offset);
        List<UserAdminRecord> rows = ids.stream().map(this::findById).flatMap(Optional::stream).toList();
        return new UserPage(rows, total);
    }

    public Optional<UserAdminRecord> findById(long id) {
        List<UserAdminRecord> rows = jdbc.query(
                "SELECT id, username, display_name, status, created_at FROM sys_user WHERE id = ?",
                (rs, i) -> new UserAdminRecord(rs.getLong("id"), rs.getString("username"),
                        rs.getString("display_name"), rs.getString("status"), List.of(),
                        rs.getTimestamp("created_at").toInstant()),
                id);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        UserAdminRecord base = rows.get(0);
        List<String> roles = jdbc.queryForList(
                "SELECT r.code FROM sys_user_role ur JOIN sys_role r ON r.id = ur.role_id"
                        + " WHERE ur.user_id = ? ORDER BY r.code",
                String.class, id);
        return Optional.of(new UserAdminRecord(base.id(), base.username(), base.displayName(),
                base.status(), roles, base.createdAt()));
    }

    public long insertUser(String username, String passwordHash, String displayName) {
        jdbc.update("INSERT INTO sys_user (username, password_hash, display_name) VALUES (?, ?, ?)",
                username, passwordHash, displayName);
        Long id = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
        return id == null ? -1 : id;
    }

    /** 角色全量替换（权限变化，事务内删除+重插；docs/09 P03：记录权限变化审计）。 */
    @Transactional
    public void replaceRoles(long userId, List<String> roleCodes) {
        jdbc.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
        for (String roleCode : roleCodes) {
            jdbc.update("INSERT INTO sys_user_role (user_id, role_id)"
                    + " SELECT ?, id FROM sys_role WHERE code = ?", userId, roleCode);
        }
    }
}
