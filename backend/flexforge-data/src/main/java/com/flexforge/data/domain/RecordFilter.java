package com.flexforge.data.domain;

import com.flexforge.common.PublicApi;
import com.flexforge.meta.domain.FieldTypeRegistry;

/**
 * 记录过滤条件（白名单构造）：field 必须来自实体定义，operator ∈ ViewRules.FILTER_OPERATORS
 * 且与字段类型兼容（contains 仅 text/enum；gte/lte 仅 integer/decimal/date），
 * value 为按类型解析后的强类型值（Long/BigDecimal/LocalDate/Boolean/String），绑定参数化。
 */
@PublicApi
public record RecordFilter(String field, String operator, Object value,
                           FieldTypeRegistry.FieldType type) {

    public RecordFilter {
        java.util.Objects.requireNonNull(field, "field");
        java.util.Objects.requireNonNull(operator, "operator");
        java.util.Objects.requireNonNull(value, "value");
        java.util.Objects.requireNonNull(type, "type");
    }
}
