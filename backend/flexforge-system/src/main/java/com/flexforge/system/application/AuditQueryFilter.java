package com.flexforge.system.application;

import com.flexforge.common.PublicApi;

import java.time.Instant;

/**
 * 审计查询过滤条件（全部可选；from/to 为 ISO-8601，时间窗前闭后开）。
 */
@PublicApi
public record AuditQueryFilter(Instant from, Instant to, String actor, String action, String objectId) {

    public AuditQueryFilter {
        actor = actor == null || actor.isBlank() ? null : actor;
        action = action == null || action.isBlank() ? null : action;
        objectId = objectId == null || objectId.isBlank() ? null : objectId;
    }
}
