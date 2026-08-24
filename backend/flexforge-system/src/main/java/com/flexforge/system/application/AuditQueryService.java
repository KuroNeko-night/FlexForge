package com.flexforge.system.application;

import com.flexforge.common.PublicApi;
import com.flexforge.common.api.PageQuery;
import com.flexforge.common.api.PageResult;
import com.flexforge.common.audit.AuditEvent;
import com.flexforge.system.infrastructure.JdbcAuditQueryRepository;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 审计查询用例（docs/09 P03：按时间、操作者、对象过滤，支撑演示"查看关键操作审计日志"；
 * docs/00 §5 步骤 6）。仅管理员（控制器 @RequireRole 声明）；排序仅允许 occurredAt。
 */
@PublicApi
@Service
public class AuditQueryService {

    public static final Set<String> AUDIT_SORT_FIELDS = Set.of("occurredAt");

    private final JdbcAuditQueryRepository repository;

    public AuditQueryService(JdbcAuditQueryRepository repository) {
        this.repository = repository;
    }

    public PageResult<AuditEvent> query(int page, int pageSize, String sortBy,
                                        PageQuery.SortDirection direction, AuditQueryFilter filter) {
        PageQuery query = direction == null
                ? PageQuery.of(page, pageSize, sortBy, AUDIT_SORT_FIELDS)
                : PageQuery.of(page, pageSize, sortBy, direction, AUDIT_SORT_FIELDS);
        JdbcAuditQueryRepository.AuditPage result = repository.query(query, filter);
        return new PageResult<>(result.rows(), result.total(), query.pageNumber(), query.pageSize());
    }
}
