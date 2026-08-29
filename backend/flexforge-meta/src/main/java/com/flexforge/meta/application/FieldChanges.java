package com.flexforge.meta.application;

import com.flexforge.meta.domain.EntityDefinition;
import com.flexforge.meta.domain.EntityRecord;
import com.flexforge.meta.domain.EntityStatus;
import com.flexforge.meta.domain.FieldDefinition;
import com.flexforge.meta.domain.FieldTypeRegistry;
import com.flexforge.meta.domain.Identifiers;
import tools.jackson.databind.JsonNode;

/**
 * 字段写入与合并规则（EntityAdminService 的静态协作者，docs/09 P04 breaking 口径）：
 * 语义列（name/type/required/validation/defaultValue）仅 draft 实体可改；
 * 表现层（displayName/rendererId/position）随时可改；合并结果整体经 FieldTypeRegistry 重校验。
 */
final class FieldChanges {

    /** 字段排序位置上限（纯展示序，非行数约束；显式给值须落在 0..999）。 */
    private static final int POSITION_CAP = 999;

    private FieldChanges() {
    }

    /** 合并 PATCH 命令与当前字段（null = 不变），产出待落库镜像。 */
    static FieldDefinition merge(FieldDefinition current, EntityAdminService.FieldCommand cmd,
                                 EntityRecord entity, EntityDefinition definition) {
        String name = mergedName(current, cmd, entity, definition);
        String fieldType = mergedSemantics(current.fieldType(), cmd.fieldType(), entity, "字段改类型");
        JsonNode validation = mergedJson(current.validation(), cmd.validation(), entity, "改校验规则");
        JsonNode defaultValue = mergedJson(current.defaultValue(), cmd.defaultValue(), entity, "改默认值");
        boolean required = mergedRequired(current, cmd, entity);
        String displayName = cmd.displayName() == null ? current.displayName() : cmd.displayName();
        Identifiers.validateDisplayName(displayName, "字段显示名");
        FieldTypeRegistry.FieldType type = FieldTypeRegistry.require(fieldType);
        String rendererId = mergedRenderer(current, cmd, type);
        int position = cmd.position() == null ? current.position() : cmd.position();
        if (cmd.position() != null) {
            requirePositionInRange(cmd.position());
        }
        FieldTypeRegistry.validateRules(type, validation);
        FieldTypeRegistry.validateDefaultValue(type, defaultValue, validation);
        return new FieldDefinition(current.id(), current.entityId(), name, displayName, fieldType,
                required, defaultValue, validation, rendererId, position);
    }

    /** 语义列是否变化（决定更新是否携带 draft 守卫）。 */
    static boolean isSemanticChange(FieldDefinition current, FieldDefinition merged) {
        return !merged.name().equals(current.name())
                || !merged.fieldType().equals(current.fieldType())
                || merged.required() != current.required()
                || !jsonEquals(current.validation(), merged.validation())
                || !jsonEquals(current.defaultValue(), merged.defaultValue());
    }

    /** 新增字段的 renderer 解析：缺省取类型默认；显式值必须与类型匹配。 */
    static String rendererOf(FieldTypeRegistry.FieldType type, String rendererId) {
        if (rendererId == null) {
            return FieldTypeRegistry.contract(type).defaultRendererId();
        }
        if (!FieldTypeRegistry.isRendererAllowedFor(type, rendererId)) {
            throw new IllegalArgumentException("rendererId 须为该字段类型的内置 renderer: " + rendererId);
        }
        return rendererId;
    }

    /** 新增字段位置：显式给值须在 0..999；缺省追加到末尾。 */
    static int positionOf(EntityDefinition definition, Integer position) {
        if (position == null) {
            return definition.fields().stream().mapToInt(FieldDefinition::position).max().orElse(0) + 1;
        }
        requirePositionInRange(position);
        return position;
    }

    private static String mergedName(FieldDefinition current, EntityAdminService.FieldCommand cmd,
                                     EntityRecord entity, EntityDefinition definition) {
        if (cmd.name() == null || cmd.name().equals(current.name())) {
            return current.name();
        }
        requireDraft(entity, "字段改名");
        Identifiers.validateName(cmd.name(), "字段名");
        boolean taken = definition.fields().stream()
                .anyMatch(f -> !f.id().equals(current.id()) && f.name().equals(cmd.name()));
        if (taken) {
            throw new IllegalArgumentException("字段名已存在: " + cmd.name());
        }
        boolean referenced = definition.views().stream()
                .anyMatch(view -> references(view.columns(), current.name())
                        || references(view.filters(), current.name()));
        // draft 实体同样拦截：无数据可孤悬，但视图配置会指向不存在的字段名，
        // 顺序约束 = 先改视图引用再改字段名
        if (referenced) {
            throw new IllegalArgumentException("字段被视图引用，先更新视图引用后再改名: " + current.name());
        }
        return cmd.name();
    }

    private static String mergedSemantics(String current, String requested,
                                          EntityRecord entity, String label) {
        if (requested == null || requested.equals(current)) {
            return current;
        }
        requireDraft(entity, label);
        return requested;
    }

    private static JsonNode mergedJson(JsonNode current, JsonNode requested,
                                       EntityRecord entity, String label) {
        // 结构相等视为"未变更"：PATCH 回传与现状相同的值不算语义变更，不触发 draft 要求
        if (requested == null || requested.equals(current)) {
            return current;
        }
        requireDraft(entity, label);
        return requested;
    }

    private static boolean mergedRequired(FieldDefinition current, EntityAdminService.FieldCommand cmd,
                                          EntityRecord entity) {
        if (cmd.required() == null || cmd.required() == current.required()) {
            return current.required();
        }
        requireDraft(entity, "改字段必填");
        return cmd.required();
    }

    private static String mergedRenderer(FieldDefinition current, EntityAdminService.FieldCommand cmd,
                                         FieldTypeRegistry.FieldType type) {
        if (cmd.rendererId() == null || cmd.rendererId().equals(current.rendererId())) {
            return current.rendererId();
        }
        if (!FieldTypeRegistry.isRendererAllowedFor(type, cmd.rendererId())) {
            throw new IllegalArgumentException(
                    "rendererId 须为该字段类型的内置 renderer: " + cmd.rendererId());
        }
        return cmd.rendererId();
    }

    private static boolean references(JsonNode items, String fieldName) {
        if (items == null || !items.isArray()) {
            return false;
        }
        for (JsonNode item : items) {
            JsonNode field = item.get("field");
            if (field != null && field.isTextual() && fieldName.equals(field.asText())) {
                return true;
            }
        }
        return false;
    }

    private static void requireDraft(EntityRecord entity, String operation) {
        if (entity.status() != EntityStatus.DRAFT) {
            throw new IllegalArgumentException(EntityAdminService.BREAKING_PREFIX + operation
                    + "（当前状态 " + entity.status().wireName() + "）");
        }
    }

    private static void requirePositionInRange(int position) {
        if (position < 0 || position > POSITION_CAP) {
            throw new IllegalArgumentException("position 必须在 0.." + POSITION_CAP);
        }
    }

    private static boolean jsonEquals(JsonNode left, JsonNode right) {
        return left == null ? right == null : left.equals(right);
    }
}
