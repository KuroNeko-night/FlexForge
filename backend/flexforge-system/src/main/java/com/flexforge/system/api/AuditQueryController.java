package com.flexforge.system.api;

import com.flexforge.auth.Roles;
import com.flexforge.auth.api.RequireRole;
import com.flexforge.common.ApiConstants;
import com.flexforge.common.PublicApi;
import com.flexforge.common.api.PageQuery;
import com.flexforge.common.api.PageResult;
import com.flexforge.common.audit.AuditEvent;
import com.flexforge.system.application.AuditQueryFilter;
import com.flexforge.system.application.AuditQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计查询接口（docs/09 P03，仅管理员）：按时间窗口/操作者/动作/对象过滤 + 分页；
 * 过滤条件经 {@link AuditQueryFilter} 绑定（ISO-8601 时间，如 from=2026-08-24T00:00:00Z）。
 */
@PublicApi
@RestController
@RequestMapping(ApiConstants.API_V1 + "/system/audit-events")
public class AuditQueryController {

    private final AuditQueryService auditQueryService;

    public AuditQueryController(AuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    @GetMapping
    @RequireRole(Roles.ADMIN)
    public PageResult<AuditEvent> query(AuditQueryFilter filter,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int pageSize,
                                        @RequestParam(required = false) String sortBy,
                                        @RequestParam(required = false) PageQuery.SortDirection sortDirection) {
        return auditQueryService.query(page, pageSize, sortBy, sortDirection, filter);
    }
}
