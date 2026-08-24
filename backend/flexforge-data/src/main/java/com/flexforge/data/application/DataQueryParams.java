package com.flexforge.data.application;

import com.flexforge.data.domain.RecordFilter;
import com.flexforge.meta.domain.EntityDefinition;
import com.flexforge.meta.domain.FieldDefinition;
import com.flexforge.meta.domain.FieldTypeRegistry;
import com.flexforge.meta.domain.ViewRules;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 查询过滤参数白名单解析（NFR-SEC-02 / FR-META-05）：参数形如 {@code 字段.操作符=值}；
 * 字段必须来自实体定义，操作符必须在 ViewRules.FILTER_OPERATORS 且与字段类型兼容，
 * 值按 FieldTypeRegistry 类型解析为强类型参数（非法值在 API 边界 400）。
 */
final class DataQueryParams {

    private DataQueryParams() {
    }

    static List<RecordFilter> parseFilters(EntityDefinition entity, Map<String, String> params) {
        List<RecordFilter> filters = new ArrayList<>();
        if (params == null) {
            return filters;
        }
        for (Map.Entry<String, String> entry : params.entrySet()) {
            filters.add(parseFilter(entity, entry.getKey(), entry.getValue()));
        }
        return filters;
    }

    private static RecordFilter parseFilter(EntityDefinition entity, String key, String rawValue) {
        int dot = key.lastIndexOf('.');
        if (dot <= 0 || dot == key.length() - 1) {
            throw new IllegalArgumentException("过滤参数格式须为 字段.操作符=值: " + key);
        }
        String fieldName = key.substring(0, dot);
        String operator = key.substring(dot + 1);
        FieldDefinition field = entity.fields().stream()
                .filter(f -> f.name().equals(fieldName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("过滤字段不在实体定义内: " + fieldName));
        if (!ViewRules.FILTER_OPERATORS.contains(operator)) {
            throw new IllegalArgumentException(
                    "过滤操作符不在白名单内: " + operator + "（允许: " + ViewRules.FILTER_OPERATORS + "）");
        }
        FieldTypeRegistry.FieldType type = FieldTypeRegistry.require(field.fieldType());
        requireCompatible(operator, type, fieldName);
        Object value = parseValue(type, fieldName, rawValue);
        return new RecordFilter(fieldName, operator, value, type);
    }

    private static void requireCompatible(String operator, FieldTypeRegistry.FieldType type,
                                          String fieldName) {
        boolean range = type == FieldTypeRegistry.FieldType.INTEGER
                || type == FieldTypeRegistry.FieldType.DECIMAL
                || type == FieldTypeRegistry.FieldType.DATE;
        boolean textual = type == FieldTypeRegistry.FieldType.TEXT
                || type == FieldTypeRegistry.FieldType.ENUM;
        if (("gte".equals(operator) || "lte".equals(operator)) && !range) {
            throw new IllegalArgumentException("操作符 " + operator + " 不适用于字段类型 " + type);
        }
        if ("contains".equals(operator) && !textual) {
            throw new IllegalArgumentException("contains 仅适用于文本/枚举字段: " + fieldName);
        }
    }

    private static Object parseValue(FieldTypeRegistry.FieldType type, String fieldName, String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("过滤值不能为空: " + fieldName);
        }
        try {
            return switch (type) {
                case INTEGER -> Long.parseLong(raw);
                case DECIMAL -> new BigDecimal(raw);
                case DATE -> LocalDate.parse(raw);
                case BOOLEAN -> parseBoolean(raw);
                case TEXT, ENUM -> raw;
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new IllegalArgumentException("过滤值与字段类型不符: " + fieldName + "=" + raw);
        }
    }

    private static Boolean parseBoolean(String raw) {
        if ("true".equals(raw) || "false".equals(raw)) {
            return Boolean.valueOf(raw);
        }
        throw new IllegalArgumentException("布尔过滤值须为 true/false: " + raw);
    }
}
