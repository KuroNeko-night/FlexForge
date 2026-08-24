package com.flexforge.meta.domain;

import com.flexforge.common.PublicApi;
import tools.jackson.databind.JsonNode;

import java.util.Set;

/**
 * 视图配置校验（FR-META-03/05）：columns/filters 引用的字段必须存在于同实体；
 * filters 仅 list 视图、operator 必须在白名单内。null 视为空配置。
 * {@link #FILTER_OPERATORS} 同时是动态查询（service.data-access）的操作符契约面。
 */
@PublicApi
public final class ViewRules {

    /** 查询字段操作符白名单（P05 动态查询构造的契约面，新增为 additive）。 */
    public static final Set<String> FILTER_OPERATORS = Set.of("eq", "contains", "gte", "lte");

    private ViewRules() {
    }

    /** 校验一份视图配置（写入 API 边界调用）。 */
    public static void validate(ViewType type, String name, JsonNode columns,
                                JsonNode filters, Set<String> fieldNames) {
        Identifiers.validateDisplayName(name, "视图名");
        validateColumns(columns, fieldNames);
        validateFilters(type, filters, fieldNames);
    }

    private static void validateColumns(JsonNode columns, Set<String> fieldNames) {
        if (columns == null || columns.isNull()) {
            return;
        }
        if (!columns.isArray()) {
            throw new IllegalArgumentException("columns 必须是数组");
        }
        for (JsonNode column : columns) {
            if (!column.isObject()) {
                throw new IllegalArgumentException("columns 数组元素必须是对象");
            }
            String field = textOf(column.get("field"), "columns[].field");
            if (!fieldNames.contains(field)) {
                throw new IllegalArgumentException("columns 引用了不存在的字段: " + field);
            }
            JsonNode visible = column.get("visible");
            if (visible != null && !visible.isNull() && !visible.isBoolean()) {
                throw new IllegalArgumentException("columns[].visible 必须是布尔值");
            }
        }
    }

    private static void validateFilters(ViewType type, JsonNode filters, Set<String> fieldNames) {
        if (filters == null || filters.isNull()) {
            return;
        }
        if (type != ViewType.LIST && !filters.isEmpty()) {
            throw new IllegalArgumentException("filters 仅 list 视图允许配置");
        }
        if (!filters.isArray()) {
            throw new IllegalArgumentException("filters 必须是数组");
        }
        for (JsonNode filter : filters) {
            if (!filter.isObject()) {
                throw new IllegalArgumentException("filters 数组元素必须是对象");
            }
            String field = textOf(filter.get("field"), "filters[].field");
            if (!fieldNames.contains(field)) {
                throw new IllegalArgumentException("filters 引用了不存在的字段: " + field);
            }
            String operator = textOf(filter.get("operator"), "filters[].operator");
            if (!FILTER_OPERATORS.contains(operator)) {
                throw new IllegalArgumentException(
                        "filters[].operator 不在白名单内: " + operator + "（允许: " + FILTER_OPERATORS + "）");
            }
        }
    }

    private static String textOf(JsonNode node, String label) {
        if (node == null || !node.isTextual()) {
            throw new IllegalArgumentException(label + " 必须是字符串");
        }
        return node.asText();
    }
}
