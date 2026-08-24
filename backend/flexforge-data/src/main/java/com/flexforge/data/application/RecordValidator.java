package com.flexforge.data.application;

import com.flexforge.meta.domain.EntityDefinition;
import com.flexforge.meta.domain.FieldDefinition;
import com.flexforge.meta.domain.FieldTypeRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 实体级记录校验编排（FR-META-05 / FR-DEMO-02 示例口径）：未知字段拒绝、
 * 必填完整性、逐字段类型与规则（值校验复用 FieldTypeRegistry 单一路径）。
 * 跨字段规则不在 MVP 验收内（P08 插件层再评估）。
 */
public final class RecordValidator {

    private RecordValidator() {
    }

    /**
     * 校验并规范化记录数据（create 语义）：显式 null 值移除、缺省字段按
     * meta_field.default_value 补齐、必填与逐字段规则校验。
     */
    public static JsonNode validateForCreate(EntityDefinition entity, JsonNode payload) {
        ObjectNode data = extractKnown(entity, payload);
        applyDefaults(entity, data);
        validate(entity, data);
        return data;
    }

    /** 合并补丁并校验（update 语义）：null 移除键，其余覆盖；必填针对合并结果判定。 */
    public static JsonNode validatePatch(EntityDefinition entity, JsonNode current, JsonNode patch) {
        ObjectNode merged = (ObjectNode) current.deepCopy();
        applyPatch(entity, patch, merged);
        validate(entity, merged);
        return merged;
    }

    private static void applyPatch(EntityDefinition entity, JsonNode patch, ObjectNode merged) {
        int matched = 0;
        for (FieldDefinition field : entity.fields()) {
            JsonNode value = object(patch).get(field.name());
            if (value == null) {
                continue;
            }
            matched++;
            if (value.isNull()) {
                merged.remove(field.name());
            } else {
                merged.set(field.name(), value);
            }
        }
        requireNoUnknownKeys(object(patch), matched, entity);
    }

    private static ObjectNode extractKnown(EntityDefinition entity, JsonNode payload) {
        ObjectNode source = object(payload);
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        int matched = 0;
        for (FieldDefinition field : entity.fields()) {
            JsonNode value = source.get(field.name());
            if (value == null) {
                continue;
            }
            matched++;
            if (!value.isNull()) {
                data.set(field.name(), value);
            }
        }
        requireNoUnknownKeys(source, matched, entity);
        return data;
    }

    private static void requireNoUnknownKeys(JsonNode source, int matched, EntityDefinition entity) {
        if (source.size() > matched) {
            throw new IllegalArgumentException(
                    "载荷含实体未定义字段（实体 " + entity.name() + "，字段白名单见元数据）");
        }
    }

    private static void applyDefaults(EntityDefinition entity, ObjectNode data) {
        for (FieldDefinition field : entity.fields()) {
            JsonNode defaultValue = field.defaultValue();
            if (defaultValue != null && !defaultValue.isNull() && data.get(field.name()) == null) {
                data.set(field.name(), defaultValue.deepCopy());
            }
        }
    }

    private static void validate(EntityDefinition entity, ObjectNode data) {
        for (FieldDefinition field : entity.fields()) {
            JsonNode value = data.get(field.name());
            if (field.required() && value == null) {
                throw new IllegalArgumentException("必填字段缺失: " + field.name());
            }
            if (value != null) {
                FieldTypeRegistry.validateValue(FieldTypeRegistry.require(field.fieldType()),
                        value, field.validation());
            }
        }
    }

    private static ObjectNode object(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return JsonNodeFactory.instance.objectNode();
        }
        if (!payload.isObject()) {
            throw new IllegalArgumentException("记录数据必须是 JSON 对象");
        }
        return (ObjectNode) payload;
    }
}
