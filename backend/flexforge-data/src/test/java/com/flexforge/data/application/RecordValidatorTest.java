package com.flexforge.data.application;

import com.flexforge.meta.domain.EntityDefinition;
import com.flexforge.meta.domain.EntityStatus;
import com.flexforge.meta.domain.FieldDefinition;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * 实体级记录校验编排测试（FR-META-05；FR-DEMO-02 示例：qty min 0）。
 */
class RecordValidatorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final EntityDefinition ENTITY = new EntityDefinition("e1", "inventory_item",
            "库存项", EntityStatus.ENABLED, null, java.util.List.of(
            field("sku", "text", true, "{\"minLength\":3,\"maxLength\":32}", null),
            field("qty", "integer", true, "{\"min\":0,\"max\":10000}", null),
            field("unit_price", "decimal", false, "{\"min\":0}", null),
            field("status", "enum", false, "{\"options\":[\"in_stock\",\"sold_out\"]}", "\"in_stock\""),
            field("archived", "boolean", false, null, "false")),
            java.util.List.of());

    private static FieldDefinition field(String name, String type, boolean required,
                                         String validation, String defaultValue) {
        return new FieldDefinition("f-" + name, "e1", name, name, type, required,
                defaultValue == null ? null : JSON.readTree(defaultValue),
                validation == null ? null : JSON.readTree(validation), null, 0);
    }

    private static JsonNode json(String raw) {
        return JSON.readTree(raw);
    }

    @Test
    void createAppliesDefaultsAndDropsNulls() {
        JsonNode data = RecordValidator.validateForCreate(ENTITY,
                json("{\"sku\":\"SKU-1\",\"qty\":5,\"unit_price\":null}"));
        assertThat(data.get("sku").asText()).isEqualTo("SKU-1");
        assertThat(data.get("qty").intValue()).isEqualTo(5);
        assertThat(data.has("unit_price")).isFalse();
        assertThat(data.get("status").asText()).isEqualTo("in_stock");
        assertThat(data.get("archived").isBoolean()).isTrue();
    }

    @Test
    void unknownFieldRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RecordValidator.validateForCreate(ENTITY,
                        json("{\"sku\":\"SKU-1\",\"qty\":1,\"evil_column\":1}")))
                .withMessageContaining("未定义字段");
    }

    @Test
    void requiredMissingAndRuleViolationsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RecordValidator.validateForCreate(ENTITY, json("{\"qty\":1}")))
                .withMessageContaining("必填字段缺失: sku");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RecordValidator.validateForCreate(ENTITY,
                        json("{\"sku\":\"SKU-1\",\"qty\":-1}")))
                .withMessageContaining("min");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RecordValidator.validateForCreate(ENTITY,
                        json("{\"sku\":\"SKU-1\",\"qty\":\"many\"}")))
                .withMessageContaining("整数");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RecordValidator.validateForCreate(ENTITY,
                        json("{\"sku\":\"S\",\"qty\":1}")))
                .withMessageContaining("minLength");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RecordValidator.validateForCreate(ENTITY,
                        json("{\"sku\":\"SKU-1\",\"qty\":1,\"status\":\"unknown\"}")))
                .withMessageContaining("options");
    }

    @Test
    void patchMergesNullClearsAndValidatesMergedWhole() {
        JsonNode current = RecordValidator.validateForCreate(ENTITY,
                json("{\"sku\":\"SKU-1\",\"qty\":5,\"unit_price\":9.5}"));

        JsonNode patched = RecordValidator.validatePatch(ENTITY, current,
                json("{\"qty\":7,\"unit_price\":null}"));
        assertThat(patched.get("qty").intValue()).isEqualTo(7);
        assertThat(patched.has("unit_price")).isFalse();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> RecordValidator.validatePatch(ENTITY, current, json("{\"qty\":null}")))
                .withMessageContaining("必填字段缺失: qty");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RecordValidator.validatePatch(ENTITY, current, json("{\"qty\":999999}")))
                .withMessageContaining("max");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RecordValidator.validatePatch(ENTITY, current, json("{\"ghost\":1}")))
                .withMessageContaining("未定义字段");
    }

    @Test
    void nonObjectPayloadRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RecordValidator.validateForCreate(ENTITY, json("[1,2]")))
                .withMessageContaining("JSON 对象");
    }
}
