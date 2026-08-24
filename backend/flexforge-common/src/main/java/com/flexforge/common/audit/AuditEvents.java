package com.flexforge.common.audit;

import com.flexforge.common.PublicApi;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * 审计事件工厂（QG-4 单一实现路径）：id 统一为 "audit-"+UUID，由各写入方经本工厂构造，
 * 禁止散落手拼。口径：actor=操作者用户名（无会话场景用系统标识）；objectId=目标对象
 * 标识（优先对象 ID，不存在对象时用其名字面量）。
 */
@PublicApi
public final class AuditEvents {

    public static AuditEvent of(String actor, String action, String objectId, String result, Clock clock) {
        return new AuditEvent("audit-" + UUID.randomUUID(), actor, action, objectId, result,
                Instant.now(clock));
    }

    private AuditEvents() {
    }
}
