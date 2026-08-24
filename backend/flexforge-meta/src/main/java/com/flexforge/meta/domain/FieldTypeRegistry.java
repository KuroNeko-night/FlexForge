package com.flexforge.meta.domain;

import com.flexforge.common.PublicApi;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * 字段类型契约单点（FR-META-02/05；RB-META 单点断言目标）：六类字段白名单、
 * 每类允许的校验键、默认值校验、SQL 类型映射（P05 受控数据表使用）与内置 renderer ID
 * （extension.field-renderer 的 fieldType→rendererId 契约源，登记册 §2.2）。
 *
 * <p>六个类型名字面量只允许出现在本类与 V004 迁移的存储层 CHECK 兜底
 * （{@code FieldTypeRegistrySinglePointTest} 源扫描守护）；Controller/服务/模板
 * 禁止再出现字段类型分支。新增字段类型 = 本类加一行契约 + 新迁移，动态 CRUD 主路径不改。
 */
@PublicApi
public final class FieldTypeRegistry {

    /** 字段类型（FR-META-02 白名单全集）。 */
    @PublicApi
    public enum FieldType { TEXT, INTEGER, DECIMAL, DATE, ENUM, BOOLEAN }

    /** 单一类型的完整契约。 */
    @PublicApi
    public record TypeContract(FieldType type, String name, String sqlType,
                               String defaultRendererId, Set<String> validationKeys) {
    }

    /** text 字段 maxLength 校验上限（与 TEXT 存储容量口径一致）。 */
    public static final int TEXT_LENGTH_CAP = 2000;

    /** enum 字段选项数量上限。 */
    public static final int ENUM_OPTION_COUNT_CAP = 50;

    /** enum 单个选项长度上限。 */
    public static final int ENUM_OPTION_LENGTH_CAP = 64;

    private static final Map<String, FieldType> BY_NAME = Map.of(
            "text", FieldType.TEXT,
            "integer", FieldType.INTEGER,
            "decimal", FieldType.DECIMAL,
            "date", FieldType.DATE,
            "enum", FieldType.ENUM,
            "boolean", FieldType.BOOLEAN);

    private static final Map<FieldType, TypeContract> CONTRACTS = buildContracts();

    private FieldTypeRegistry() {
    }

    private static Map<FieldType, TypeContract> buildContracts() {
        Map<FieldType, TypeContract> map = new EnumMap<>(FieldType.class);
        map.put(FieldType.TEXT, new TypeContract(FieldType.TEXT, "text", "TEXT",
                "text.default", Set.of("minLength", "maxLength")));
        map.put(FieldType.INTEGER, new TypeContract(FieldType.INTEGER, "integer", "BIGINT",
                "integer.default", Set.of("min", "max")));
        map.put(FieldType.DECIMAL, new TypeContract(FieldType.DECIMAL, "decimal", "NUMERIC(20,6)",
                "decimal.default", Set.of("min", "max")));
        map.put(FieldType.DATE, new TypeContract(FieldType.DATE, "date", "DATE",
                "date.default", Set.of()));
        map.put(FieldType.ENUM, new TypeContract(FieldType.ENUM, "enum", "VARCHAR(64)",
                "enum.default", Set.of("options")));
        map.put(FieldType.BOOLEAN, new TypeContract(FieldType.BOOLEAN, "boolean", "BOOLEAN",
                "boolean.default", Set.of()));
        return Map.copyOf(map);
    }

    /** 类型名全集（白名单）。 */
    public static Set<String> typeNames() {
        return BY_NAME.keySet();
    }

    /** 按名取类型；未知类型在 API 边界拒绝（FR-META-05）。 */
    public static FieldType require(String name) {
        FieldType type = name == null ? null : BY_NAME.get(name);
        if (type == null) {
            throw new IllegalArgumentException("未知字段类型: " + name + "（白名单: " + typeNames() + "）");
        }
        return type;
    }

    /** 取单一类型契约。 */
    public static TypeContract contract(FieldType type) {
        TypeContract contract = CONTRACTS.get(type);
        if (contract == null) {
            throw new IllegalArgumentException("未知字段类型枚举: " + type);
        }
        return contract;
    }

    /** 内置 renderer ID 全集（平台契约，P06 前端 registry 按 ID 实现组件）。 */
    public static Set<String> builtInRendererIds() {
        Set<String> ids = new HashSet<>();
        for (TypeContract contract : CONTRACTS.values()) {
            ids.add(contract.defaultRendererId());
        }
        return Set.copyOf(ids);
    }

    /** renderer ID 必须是平台内置 ID（FR-META-05；登记册 §2.2 extension.field-renderer）。 */
    public static boolean isBuiltInRendererId(String rendererId) {
        return rendererId != null && builtInRendererIds().contains(rendererId);
    }

    /**
     * renderer 与字段类型的匹配校验（extension.field-renderer 载荷 {fieldType, rendererId}）：
     * P04 每类型仅允许其默认 renderer；P06+ 前端 renderer registry 扩展同类型候选时在此加集。
     */
    public static boolean isRendererAllowedFor(FieldType type, String rendererId) {
        return rendererId != null && contract(type).defaultRendererId().equals(rendererId);
    }

    /**
     * 校验规则对象：键必须在类型白名单内且值合法（FR-META-05）。
     * {@code rules} 为 null 或 JSON null 表示无规则。
     */
    public static void validateRules(FieldType type, JsonNode rules) {
        if (rules == null || rules.isNull()) {
            return;
        }
        if (!rules.isObject()) {
            throw new IllegalArgumentException("validation 必须是 JSON 对象");
        }
        TypeContract contract = contract(type);
        int recognized = 0;
        for (String key : contract.validationKeys()) {
            JsonNode value = rules.get(key);
            if (value != null && !value.isNull()) {
                recognized++;
                validateRuleValue(type, key, value);
            }
        }
        if (rules.size() > recognized) {
            throw new IllegalArgumentException(
                    "类型 " + contract.name() + " 不允许该校验键（允许: " + contract.validationKeys() + "）");
        }
        validateRuleCrossing(type, rules);
    }

    /** 默认值必须符合类型与已通过的校验规则；null 表示无默认值。 */
    public static void validateDefaultValue(FieldType type, JsonNode value, JsonNode rules) {
        if (value == null || value.isNull()) {
            return;
        }
        validateDefaultOfType(type, value, rules);
    }

    /**
     * 记录值校验（service.data-access / P05）：与默认值共用同一值-规则校验路径
     * （QG-4 单一实现），供动态 CRUD 对每个字段值执行类型与规则校验。
     */
    public static void validateValue(FieldType type, JsonNode value, JsonNode rules) {
        validateDefaultValue(type, value, rules);
    }

    private static void validateDefaultOfType(FieldType type, JsonNode value, JsonNode rules) {
        switch (type) {
            case TEXT -> validateTextDefault(value, rules);
            case INTEGER -> validateIntegerDefault(value, rules);
            case DECIMAL -> validateDecimalDefault(value, rules);
            case DATE -> validateDateDefault(value);
            case ENUM -> validateEnumDefault(value, rules);
            case BOOLEAN -> {
                if (!value.isBoolean()) {
                    throw new IllegalArgumentException("boolean 默认值必须是 true/false");
                }
            }
        }
    }

    private static void validateRuleValue(FieldType type, String key, JsonNode value) {
        switch (key) {
            case "minLength" -> requireInt(value, 0, TEXT_LENGTH_CAP, "minLength");
            case "maxLength" -> requireInt(value, 1, TEXT_LENGTH_CAP, "maxLength");
            case "min", "max" -> validateRangeValue(type, key, value);
            case "options" -> validateOptions(value);
            default -> throw new IllegalArgumentException("未知校验键: " + key);
        }
    }

    private static void validateRangeValue(FieldType type, String key, JsonNode value) {
        if (type == FieldType.INTEGER) {
            if (!value.isInt() && !value.isLong()) {
                throw new IllegalArgumentException(key + " 必须是整数");
            }
            return;
        }
        if (!value.isNumber()) {
            throw new IllegalArgumentException(key + " 必须是数值");
        }
    }

    private static void validateRuleCrossing(FieldType type, JsonNode rules) {
        if (present(rules.get("min")) && present(rules.get("max"))) {
            validateRangeCrossing(rules);
        }
        if (present(rules.get("minLength")) && present(rules.get("maxLength"))) {
            validateLengthCrossing(rules);
        }
    }

    private static void validateRangeCrossing(JsonNode rules) {
        if (decimalOf(rules.get("min")).compareTo(decimalOf(rules.get("max"))) > 0) {
            throw new IllegalArgumentException("min 不能大于 max");
        }
    }

    private static void validateLengthCrossing(JsonNode rules) {
        if (rules.get("minLength").intValue() > rules.get("maxLength").intValue()) {
            throw new IllegalArgumentException("minLength 不能大于 maxLength");
        }
    }

    private static boolean present(JsonNode node) {
        return node != null && !node.isNull();
    }

    private static void validateOptions(JsonNode value) {
        if (!value.isArray() || value.isEmpty()) {
            throw new IllegalArgumentException("options 必须是非空数组");
        }
        if (value.size() > ENUM_OPTION_COUNT_CAP) {
            throw new IllegalArgumentException("options 数量上限 " + ENUM_OPTION_COUNT_CAP);
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode option : value) {
            String text = optionAsString(option, "options 数组元素");
            if (text.length() > ENUM_OPTION_LENGTH_CAP) {
                throw new IllegalArgumentException("options 单项长度上限 " + ENUM_OPTION_LENGTH_CAP);
            }
            if (!seen.add(text)) {
                throw new IllegalArgumentException("options 存在重复项: " + text);
            }
        }
    }

    private static void validateTextDefault(JsonNode value, JsonNode rules) {
        String text = optionAsString(value, "text 默认值");
        JsonNode minLength = rules == null ? null : rules.get("minLength");
        if (minLength != null && !minLength.isNull() && text.length() < minLength.intValue()) {
            throw new IllegalArgumentException("text 默认值长度低于 minLength " + minLength.intValue());
        }
        JsonNode maxLength = rules == null ? null : rules.get("maxLength");
        int max = maxLength != null && !maxLength.isNull() ? maxLength.intValue() : TEXT_LENGTH_CAP;
        if (text.length() > max) {
            throw new IllegalArgumentException("text 默认值长度超出 maxLength " + max);
        }
    }

    private static void validateIntegerDefault(JsonNode value, JsonNode rules) {
        if (!value.isInt() && !value.isLong()) {
            throw new IllegalArgumentException("integer 默认值必须是整数");
        }
        checkRange(value, rules, "integer 默认值");
    }

    private static void validateDecimalDefault(JsonNode value, JsonNode rules) {
        if (!value.isNumber()) {
            throw new IllegalArgumentException("decimal 默认值必须是数值");
        }
        checkRange(value, rules, "decimal 默认值");
    }

    private static void validateDateDefault(JsonNode value) {
        try {
            LocalDate.parse(optionAsString(value, "date 默认值"));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("date 默认值必须是 ISO-8601 日期（yyyy-MM-dd）");
        }
    }

    private static void validateEnumDefault(JsonNode value, JsonNode rules) {
        JsonNode options = rules == null ? null : rules.get("options");
        if (options == null || !options.isArray()) {
            throw new IllegalArgumentException("enum 默认值要求 validation.options 先行提供");
        }
        String text = optionAsString(value, "enum 默认值");
        for (JsonNode option : options) {
            if (text.equals(option.asText())) {
                return;
            }
        }
        throw new IllegalArgumentException("enum 默认值不在 options 内: " + text);
    }

    private static void checkRange(JsonNode value, JsonNode rules, String label) {
        JsonNode min = rules == null ? null : rules.get("min");
        JsonNode max = rules == null ? null : rules.get("max");
        BigDecimal actual = decimalOf(value);
        if (min != null && !min.isNull() && actual.compareTo(decimalOf(min)) < 0) {
            throw new IllegalArgumentException(label + " 低于 min");
        }
        if (max != null && !max.isNull() && actual.compareTo(decimalOf(max)) > 0) {
            throw new IllegalArgumentException(label + " 超出 max");
        }
    }

    private static void requireInt(JsonNode value, int min, int max, String label) {
        if (!value.isInt() || value.intValue() < min || value.intValue() > max) {
            throw new IllegalArgumentException(label + " 必须是 " + min + ".." + max + " 的整数");
        }
    }

    private static BigDecimal decimalOf(JsonNode value) {
        return value.isInt() || value.isLong() ? BigDecimal.valueOf(value.longValue())
                : value.decimalValue();
    }

    private static String optionAsString(JsonNode value, String label) {
        if (!value.isTextual()) {
            throw new IllegalArgumentException(label + " 必须是字符串");
        }
        return value.asText();
    }
}
