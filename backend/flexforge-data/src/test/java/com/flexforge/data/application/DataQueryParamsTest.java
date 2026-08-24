package com.flexforge.data.application;

import com.flexforge.data.domain.RecordFilter;
import com.flexforge.meta.domain.EntityDefinition;
import com.flexforge.meta.domain.EntityStatus;
import com.flexforge.meta.domain.FieldDefinition;
import com.flexforge.meta.domain.FieldTypeRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * 查询过滤参数白名单解析测试（NFR-SEC-02：注入样例一律 400，不进 SQL）。
 */
class DataQueryParamsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final EntityDefinition ENTITY = new EntityDefinition("e1", "inventory_item",
            "库存项", EntityStatus.ENABLED, null, List.of(
            new FieldDefinition("f1", "e1", "sku", "SKU", "text", false, null, null, null, 0),
            new FieldDefinition("f2", "e1", "qty", "数量", "integer", false, null,
                    JSON.readTree("{\"min\":0}"), null, 1),
            new FieldDefinition("f3", "e1", "expiry_date", "到期日", "date", false, null, null, null, 2)),
            List.of());

    @Test
    void parsesTypedFilters() {
        List<RecordFilter> filters = DataQueryParams.parseFilters(ENTITY, Map.of(
                "sku.contains", "SKU-1",
                "qty.gte", "5",
                "expiry_date.lte", "2027-01-01"));
        assertThat(filters).hasSize(3);
        assertThat(filters).anySatisfy(f -> {
            assertThat(f.field()).isEqualTo("qty");
            assertThat(f.operator()).isEqualTo("gte");
            assertThat(f.value()).isEqualTo(5L);
            assertThat(f.type()).isEqualTo(FieldTypeRegistry.FieldType.INTEGER);
        });
    }

    @Test
    void injectionShapedInputsRejectedAtBoundary() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DataQueryParams.parseFilters(ENTITY,
                        Map.of("sku; DROP TABLE data_record;--.contains", "x")))
                .withMessageContaining("过滤字段不在实体定义内");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DataQueryParams.parseFilters(ENTITY,
                        Map.of("sku.regex", "x")))
                .withMessageContaining("操作符不在白名单内");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DataQueryParams.parseFilters(ENTITY,
                        Map.of("qty", "5")))
                .withMessageContaining("字段.操作符");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DataQueryParams.parseFilters(ENTITY,
                        Map.of("qty.gte", "five")))
                .withMessageContaining("字段类型不符");
    }

    @Test
    void operatorTypeCompatibilityEnforced() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DataQueryParams.parseFilters(ENTITY, Map.of("qty.contains", "5")))
                .withMessageContaining("contains");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DataQueryParams.parseFilters(ENTITY, Map.of("sku.gte", "a")))
                .withMessageContaining("gte");
    }
}
