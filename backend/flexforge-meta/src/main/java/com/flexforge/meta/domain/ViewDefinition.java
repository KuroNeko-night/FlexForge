package com.flexforge.meta.domain;

import com.flexforge.common.PublicApi;
import tools.jackson.databind.JsonNode;

/**
 * 视图定义（meta_view 行镜像）：columns 按数组序展示 {@code [{field, visible}]}，
 * filters 仅 list 视图 {@code [{field, operator}]}（写入前经 ViewRules 白名单校验）；
 * groupBy 仅 kanban 视图（分列 enum 字段名，ViewRules 校验必填且为 enum，P17）。
 */
@PublicApi
public record ViewDefinition(
        String id,
        String entityId,
        String viewType,
        String name,
        JsonNode columns,
        JsonNode filters,
        String groupBy) {
}
