package com.flexforge.ai.spec;

import com.flexforge.common.PublicApi;
import com.flexforge.meta.domain.FieldTypeRegistry;
import com.flexforge.meta.domain.Identifiers;
import com.flexforge.meta.domain.ViewRules;
import com.flexforge.meta.domain.ViewType;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 版本化 RequirementSpec JSON Schema（docs/09 P10、FR-ISSUE-04）：
 * AI 输出/手工规格校验的唯一事实源。v1 覆盖实体、字段、视图、权限、
 * 业务规则与验收标准；校验复用平台 FieldTypeRegistry/ViewRules 契约
 * （六类字段/校验键/视图列白名单），保证规格→插件资源语义一致。
 * 纯函数：validate 返回错误清单，空清单即合法；不抛异常。
 */
@PublicApi
public final class RequirementSchema {

    public static final int CURRENT_VERSION = 1;

    private static final Pattern KEY_PATTERN =
            Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z0-9_]*)*$");

    private RequirementSchema() {
    }

    public static List<String> validate(JsonNode spec) {
        List<String> errors = new ArrayList<>();
        if (spec == null || !spec.isObject()) {
            return List.of("规格必须是 JSON 对象");
        }
        requireSchemaVersion(spec, errors);
        requireText(spec.get("summary"), "summary", 200, errors);
        Map<String, Set<String>> fieldsByEntity = validateEntities(spec.get("entities"), errors);
        validateViews(spec.get("views"), fieldsByEntity, errors);
        validateKeys(spec.get("permissions"), "permissions", errors);
        validateRules(spec.get("rules"), errors);
        validateAcceptance(spec.get("acceptance"), errors);
        return List.copyOf(errors);
    }

    private static void requireSchemaVersion(JsonNode spec, List<String> errors) {
        JsonNode version = spec.get("schemaVersion");
        if (version == null || !version.isInt() || version.intValue() != CURRENT_VERSION) {
            errors.add("schemaVersion 必须为 " + CURRENT_VERSION);
        }
    }

    private static Map<String, Set<String>> validateEntities(JsonNode entities,
                                                             List<String> errors) {
        Map<String, Set<String>> fieldsByEntity = new HashMap<>();
        if (entities == null || !entities.isArray() || entities.isEmpty()) {
            errors.add("entities 必须是非空数组");
            return fieldsByEntity;
        }
        for (int i = 0; i < entities.size(); i++) {
            validateEntity(entities.get(i), "entities[" + i + "]", fieldsByEntity, errors);
        }
        return fieldsByEntity;
    }

    private static void validateEntity(JsonNode entity, String label,
                                       Map<String, Set<String>> fieldsByEntity,
                                       List<String> errors) {
        if (!entity.isObject()) {
            errors.add(label + " 必须是对象");
            return;
        }
        String name = validatedEntityName(entity, label, fieldsByEntity, errors);
        requireText(entity.get("displayName"), label + ".displayName", 100, errors);
        if (name != null) {
            fieldsByEntity.put(name, validateFields(entity.get("fields"), label, errors));
        }
    }

    /** 校验实体名（平台 Identifiers 单点：snake 标识 + 63 上限 + 不重复）。 */
    private static String validatedEntityName(JsonNode entity, String label,
                                              Map<String, Set<String>> fieldsByEntity,
                                              List<String> errors) {
        String name = textOf(entity.get("name"), label + ".name", errors);
        if (name == null) {
            return null;
        }
        try {
            Identifiers.validateName(name, label + ".name");
        } catch (RuntimeException e) {
            errors.add(e.getMessage());
            return null;
        }
        if (fieldsByEntity.containsKey(name)) {
            errors.add(label + ".name 重复: " + name);
            return null;
        }
        return name;
    }

    private static Set<String> validateFields(JsonNode fields, String label,
                                              List<String> errors) {
        Set<String> fieldNames = new HashSet<>();
        if (fields == null || !fields.isArray() || fields.isEmpty()) {
            errors.add(label + ".fields 必须是非空数组");
            return fieldNames;
        }
        for (int i = 0; i < fields.size(); i++) {
            String name = validateField(fields.get(i), label + ".fields[" + i + "]", errors);
            if (name != null && !fieldNames.add(name)) {
                errors.add(label + ".fields 名称重复: " + name);
            }
        }
        return fieldNames;
    }

    private static String validateField(JsonNode field, String label, List<String> errors) {
        if (!field.isObject()) {
            errors.add(label + " 必须是对象");
            return null;
        }
        String name = textOf(field.get("name"), label + ".name", errors);
        if (name != null) {
            try {
                Identifiers.validateName(name, label + ".name");
            } catch (RuntimeException e) {
                errors.add(e.getMessage());
                name = null;
            }
        }
        validateFieldType(field, label, errors);
        return name;
    }

    private static void validateFieldType(JsonNode field, String label, List<String> errors) {
        JsonNode type = field.get("fieldType");
        if (type == null || !type.isTextual()) {
            errors.add(label + ".fieldType 必填");
            return;
        }
        try {
            FieldTypeRegistry.FieldType fieldType = FieldTypeRegistry.require(type.asText());
            validateFieldRules(fieldType, field.get("validation"), label, errors);
        } catch (RuntimeException e) {
            errors.add(label + ".fieldType: " + e.getMessage());
        }
    }

    private static void validateFieldRules(FieldTypeRegistry.FieldType fieldType, JsonNode validation,
                                           String label, List<String> errors) {
        if (validation == null || validation.isNull()) {
            return;
        }
        try {
            FieldTypeRegistry.validateRules(fieldType, validation);
        } catch (RuntimeException e) {
            errors.add(label + ".validation: " + e.getMessage());
        }
    }

    private static void validateViews(JsonNode views, Map<String, Set<String>> fieldsByEntity,
                                      List<String> errors) {
        if (views == null || views.isNull()) {
            return;
        }
        if (!views.isArray()) {
            errors.add("views 必须是数组");
            return;
        }
        for (int i = 0; i < views.size(); i++) {
            JsonNode view = views.get(i);
            String label = "views[" + i + "]";
            String entity = textOf(view.get("entity"), label + ".entity", errors);
            if (entity != null && !fieldsByEntity.containsKey(entity)) {
                errors.add(label + ".entity 引用了未声明实体: " + entity);
            }
            try {
                ViewRules.validate(
                        ViewType.fromName(view.path("viewType").asString("")),
                        view.path("name").asString(""),
                        view.get("columns"), view.get("filters"),
                        fieldsByEntity.getOrDefault(entity, Set.of()));
            } catch (RuntimeException e) {
                errors.add(label + ": " + e.getMessage());
            }
        }
    }

    private static void validateKeys(JsonNode list, String label, List<String> errors) {
        if (list == null || list.isNull()) {
            return;
        }
        if (!list.isArray()) {
            errors.add(label + " 必须是数组");
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            JsonNode item = list.get(i);
            if (!item.isTextual() || !KEY_PATTERN.matcher(item.asText()).matches()) {
                errors.add(label + " 含非法项: " + item);
            }
        }
    }

    private static void validateRules(JsonNode rules, List<String> errors) {
        if (rules == null || rules.isNull()) {
            return;
        }
        if (!rules.isArray()) {
            errors.add("rules 必须是数组");
            return;
        }
        for (int i = 0; i < rules.size(); i++) {
            JsonNode rule = rules.get(i);
            String label = "rules[" + i + "]";
            if (!rule.isObject()) {
                errors.add(label + " 必须是对象");
                continue;
            }
            requireText(rule.get("name"), label + ".name", 100, errors);
            requireText(rule.get("description"), label + ".description", 500, errors);
        }
    }

    private static void validateAcceptance(JsonNode acceptance, List<String> errors) {
        if (acceptance == null || !acceptance.isArray() || acceptance.isEmpty()) {
            errors.add("acceptance（验收标准）必须是非空数组");
            return;
        }
        for (int i = 0; i < acceptance.size(); i++) {
            JsonNode item = acceptance.get(i);
            if (!item.isTextual() || item.asText().isBlank()) {
                errors.add("acceptance 含空项: " + item);
            }
        }
    }

    private static void requireText(JsonNode node, String label, int maxLength,
                                    List<String> errors) {
        String value = textOf(node, label, errors);
        if (value != null && value.length() > maxLength) {
            errors.add(label + " 超长（≤" + maxLength + "）");
        }
    }

    private static String textOf(JsonNode node, String label, List<String> errors) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            errors.add("缺少必填字段 " + label);
            return null;
        }
        return node.asText();
    }
}
