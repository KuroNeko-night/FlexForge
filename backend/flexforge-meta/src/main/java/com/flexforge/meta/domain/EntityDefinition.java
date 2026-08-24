package com.flexforge.meta.domain;

import com.flexforge.common.PublicApi;

import java.util.List;

/**
 * 实体完整定义（MetaRegistry 缓存单元）：实体行 + 字段（按 position/name 排序）+ 视图。
 * pluginId 非空表示插件来源（P08 安装流程写入）；null 表示开发者经配置 API 创建。
 */
@PublicApi
public record EntityDefinition(
        String id,
        String name,
        String displayName,
        EntityStatus status,
        String pluginId,
        List<FieldDefinition> fields,
        List<ViewDefinition> views) {

    public EntityDefinition {
        fields = fields == null ? List.of() : List.copyOf(fields);
        views = views == null ? List.of() : List.copyOf(views);
    }
}
