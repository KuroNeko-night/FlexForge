package com.flexforge.meta.domain;

import com.flexforge.common.PublicApi;
import tools.jackson.databind.JsonNode;

/**
 * 字段定义（meta_field 行镜像）。组件数对齐存储列（登记载荷类同口径，
 * 评审按"表行镜像"说明）：rendererId 恒为解析后的内置 ID（空入参按类型默认补齐）。
 *
 * <p>validation/defaultValue 为 JSON 原样透传（写入前经 FieldTypeRegistry 白名单校验）。
 */
@PublicApi
public record FieldDefinition(
        String id,
        String entityId,
        String name,
        String displayName,
        String fieldType,
        boolean required,
        JsonNode defaultValue,
        JsonNode validation,
        String rendererId,
        int position) {
}
