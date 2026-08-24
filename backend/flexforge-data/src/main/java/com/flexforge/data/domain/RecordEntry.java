package com.flexforge.data.domain;

import com.flexforge.common.PublicApi;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 动态记录行镜像（单 JSONB 记录表）：data 为 {字段名: 值}，键集恒 ⊆ 实体当前字段定义
 * （写入经 RecordValidator 白名单校验；启用实体上字段改名被 P04 breaking 规则阻止）。
 */
@PublicApi
public record RecordEntry(
        String id,
        String entityId,
        JsonNode data,
        Instant createdAt,
        Instant updatedAt) {
}
