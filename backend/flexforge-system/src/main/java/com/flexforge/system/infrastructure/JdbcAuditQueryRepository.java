package com.flexforge.system.infrastructure;

import com.flexforge.common.api.PageQuery;
import com.flexforge.common.audit.AuditEvent;
import com.flexforge.system.application.AuditQueryFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 审计查询仓储（system 模块 infrastructure）：按时间窗口/操作者/动作/对象过滤 + 分页；
 * 全部参数化绑定（S1），排序仅允许 occurred_at（白名单经 PageQuery 强制）。
 */
@Repository
public class JdbcAuditQueryRepository {

    private final JdbcTemplate jdbc;

    public JdbcAuditQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record AuditPage(List<AuditEvent> rows, long total) {
    }

    /** WHERE 片段与绑定参数（过滤条件均为可选，全部参数化）。 */
    private record WhereClause(String sql, List<Object> params) {
    }

    public AuditPage query(PageQuery query, AuditQueryFilter filter) {
        WhereClause where = buildWhere(filter);
        Long total = Optional.ofNullable(jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event" + where.sql(), Long.class,
                where.params().toArray())).orElse(0L);
        List<AuditEvent> rows = queryRows(query, where);
        return new AuditPage(rows, total);
    }

    private List<AuditEvent> queryRows(PageQuery query, WhereClause where) {
        String direction = query.sortDirection() == PageQuery.SortDirection.DESC ? "DESC" : "ASC";
        List<Object> params = new ArrayList<>(where.params());
        int offset = (query.pageNumber() - 1) * query.pageSize();
        params.add(query.pageSize());
        params.add(offset);
        return jdbc.query(
                "SELECT id, actor, action, object_id, result, occurred_at FROM sys_audit_event"
                        + where.sql() + " ORDER BY occurred_at " + direction + ", id ASC LIMIT ? OFFSET ?",
                (rs, i) -> new AuditEvent(rs.getString("id"), rs.getString("actor"),
                        rs.getString("action"), rs.getString("object_id"), rs.getString("result"),
                        rs.getTimestamp("occurred_at").toInstant()),
                params.toArray());
    }

    private static WhereClause buildWhere(AuditQueryFilter filter) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (filter.from() != null) {
            where.append(" AND occurred_at >= ?");
            params.add(Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            where.append(" AND occurred_at < ?");
            params.add(Timestamp.from(filter.to()));
        }
        if (filter.actor() != null) {
            where.append(" AND actor = ?");
            params.add(filter.actor());
        }
        if (filter.action() != null) {
            where.append(" AND action = ?");
            params.add(filter.action());
        }
        if (filter.objectId() != null) {
            where.append(" AND object_id = ?");
            params.add(filter.objectId());
        }
        return new WhereClause(where.toString(), params);
    }
}
