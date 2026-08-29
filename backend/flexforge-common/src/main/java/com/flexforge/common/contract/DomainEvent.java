package com.flexforge.common.contract;

import com.flexforge.common.PublicApi;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 领域事件（docs/extension-points.md §2.3 event.domain）：跨模块解耦的只读事实载体。
 *
 * <p>type 必须使用登记册 DomainEventType ID；occurredAt 统一 UTC；payload 键值不得为 null；
 * activationId 在事件由插件贡献时填写，平台自身事件为 null。
 */
@PublicApi
public record DomainEvent(
        String eventId,
        String type,
        String aggregateId,
        Instant occurredAt,
        Map<String, Object> payload,
        String activationId
) {

    public DomainEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        // Map.copyOf 一步完成不可变快照与 null 键值拒绝（对齐类契约）；仅浅层不可变，值对象自担
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
