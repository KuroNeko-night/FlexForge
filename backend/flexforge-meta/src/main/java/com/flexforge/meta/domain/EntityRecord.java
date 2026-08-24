package com.flexforge.meta.domain;

import com.flexforge.common.PublicApi;

import java.time.Instant;

/**
 * 实体轻量行（写路径状态/重名校验与列表展示用；字段/视图经 {@link EntityDefinition} 装配）。
 */
@PublicApi
public record EntityRecord(
        String id,
        String name,
        String displayName,
        EntityStatus status,
        String pluginId,
        Instant createdAt,
        Instant updatedAt) {
}
