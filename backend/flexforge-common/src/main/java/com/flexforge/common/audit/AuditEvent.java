package com.flexforge.common.audit;

import com.flexforge.common.PublicApi;

import java.time.Instant;
import java.util.Objects;

/**
 * 审计事件（docs/extension-points.md §2.1 service.audit）：关键写操作的事实记录。
 *
 * <p>result 口径：{@code success} 或 {@code failure}（P03 落库时如需扩展先登记为 additive）；
 * occurredAt 统一 UTC；id 由写入端生成，保证唯一即可。
 */
@PublicApi
public record AuditEvent(
        String id,
        String actor,
        String action,
        String objectId,
        String result,
        Instant occurredAt
) {

    public AuditEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(objectId, "objectId");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
