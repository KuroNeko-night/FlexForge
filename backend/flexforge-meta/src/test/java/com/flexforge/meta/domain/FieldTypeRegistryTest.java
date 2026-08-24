package com.flexforge.meta.domain;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * FieldTypeRegistry 契约测试（FR-META-02/05）：六类白名单、每类校验键、
 * 默认值校验、SQL/renderer 映射（RB-META）。
 */
class FieldTypeRegistryTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode json(String raw) {
        return raw == null ? null : JSON.readTree(raw);
    }

    private static void validateRules(String type, String rules) {
        FieldTypeRegistry.validateRules(FieldTypeRegistry.require(type), json(rules));
    }

    private static void validateDefault(String type, String value, String rules) {
        FieldTypeRegistry.validateDefaultValue(FieldTypeRegistry.require(type),
                json(value), rules == null ? null : json(rules));
    }

    @Test
    void sixTypeWhitelistIsClosed() {
        assertThat(FieldTypeRegistry.typeNames()).containsExactlyInAnyOrder(
                "text", "integer", "decimal", "date", "enum", "boolean");
        for (String name : FieldTypeRegistry.typeNames()) {
            assertThat(FieldTypeRegistry.contract(FieldTypeRegistry.require(name)).name())
                    .isEqualTo(name);
        }
        assertThatIllegalArgumentException().isThrownBy(() -> FieldTypeRegistry.require("string"))
                .withMessageContaining("白名单");
        assertThatIllegalArgumentException().isThrownBy(() -> FieldTypeRegistry.require(null))
                .withMessageContaining("未知字段类型");
    }

    @Test
    void contractProvidesSqlTypeAndDefaultRendererPerType() {
        assertThat(FieldTypeRegistry.contract(FieldTypeRegistry.FieldType.TEXT).sqlType()).isEqualTo("TEXT");
        assertThat(FieldTypeRegistry.contract(FieldTypeRegistry.FieldType.INTEGER).sqlType()).isEqualTo("BIGINT");
        assertThat(FieldTypeRegistry.contract(FieldTypeRegistry.FieldType.DECIMAL).sqlType()).isEqualTo("NUMERIC(20,6)");
        assertThat(FieldTypeRegistry.contract(FieldTypeRegistry.FieldType.DATE).sqlType()).isEqualTo("DATE");
        assertThat(FieldTypeRegistry.contract(FieldTypeRegistry.FieldType.ENUM).sqlType()).isEqualTo("VARCHAR(64)");
        assertThat(FieldTypeRegistry.contract(FieldTypeRegistry.FieldType.BOOLEAN).sqlType()).isEqualTo("BOOLEAN");
        assertThat(FieldTypeRegistry.builtInRendererIds()).containsExactlyInAnyOrder(
                "text.default", "integer.default", "decimal.default",
                "date.default", "enum.default", "boolean.default");
        assertThat(FieldTypeRegistry.isBuiltInRendererId("enum.default")).isTrue();
        assertThat(FieldTypeRegistry.isBuiltInRendererId("custom.renderer")).isFalse();
        assertThat(FieldTypeRegistry.isBuiltInRendererId(null)).isFalse();

        // renderer 与字段类型必须匹配（extension.field-renderer 载荷契约，P04 每类型仅默认 renderer）
        assertThat(FieldTypeRegistry.isRendererAllowedFor(FieldTypeRegistry.FieldType.INTEGER,
                "integer.default")).isTrue();
        assertThat(FieldTypeRegistry.isRendererAllowedFor(FieldTypeRegistry.FieldType.INTEGER,
                "text.default")).isFalse();
        assertThat(FieldTypeRegistry.isRendererAllowedFor(FieldTypeRegistry.FieldType.INTEGER,
                null)).isFalse();
    }

    @Test
    void validationKeysAreWhitelistedPerType() {
        validateRules("text", "{\"minLength\":1,\"maxLength\":32}");
        validateRules("integer", "{\"min\":0,\"max\":10}");
        validateRules("decimal", "{\"min\":0.5}");
        validateRules("enum", "{\"options\":[\"a\",\"b\"]}");
        validateRules("boolean", null);
        validateRules("date", "{}");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> validateRules("text", "{\"min\":1}"))
                .withMessageContaining("不允许");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> validateRules("integer", "{\"options\":[\"a\"]}"))
                .withMessageContaining("不允许");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> validateRules("date", "{\"min\":\"2026-01-01\"}"))
                .withMessageContaining("不允许");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> validateRules("text", "{\"minLength\":\"3\"}"))
                .withMessageContaining("minLength");
    }

    @Test
    void crossingAndRangesAreValidated() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> validateRules("text", "{\"minLength\":5,\"maxLength\":3}"))
                .withMessageContaining("minLength");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> validateRules("integer", "{\"min\":10,\"max\":1}"))
                .withMessageContaining("min");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> validateRules("integer", "{\"max\":\"x\"}"))
                .withMessageContaining("max");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> validateRules("decimal", "{\"min\":\"x\"}"))
                .withMessageContaining("min");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> validateRules("text", "{\"maxLength\":5000}"))
                .withMessageContaining("maxLength");
    }

    @Test
    void enumOptionsAreValidated() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> validateRules("enum", "{\"options\":[]}"))
                .withMessageContaining("options");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> validateRules("enum", "{\"options\":[\"a\",\"a\"]}"))
                .withMessageContaining("重复");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> validateRules("enum", "{\"options\":[\"" + "x".repeat(65) + "\"]}"))
                .withMessageContaining("长度");
        StringBuilder tooMany = new StringBuilder("{\"options\":[");
        for (int i = 0; i < 51; i++) {
            tooMany.append("\"o").append(i).append("\",");
        }
        tooMany.append("\"o52\"]}");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> validateRules("enum", tooMany.toString()))
                .withMessageContaining("数量");
    }

    @Test
    void defaultValuesConformToTypeAndRules() {
        validateDefault("text", "\"abc\"", "{\"minLength\":2,\"maxLength\":5}");
        validateDefault("integer", "3", "{\"min\":0,\"max\":10}");
        validateDefault("decimal", "1.5", "{\"min\":0}");
        validateDefault("date", "\"2027-01-01\"", null);
        validateDefault("enum", "\"a\"", "{\"options\":[\"a\",\"b\"]}");
        validateDefault("boolean", "false", null);
        validateDefault("text", null, null);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> validateDefault("text", "\"abcdef\"", "{\"maxLength\":3}"))
                .withMessageContaining("maxLength");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> validateDefault("integer", "1.5", null))
                .withMessageContaining("整数");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> validateDefault("integer", "11", "{\"max\":10}"))
                .withMessageContaining("max");
        // NUMERIC(20,6) 精度防线（P05 审查 P1）：整数位 >14 或小数位 >6 拒绝。
        // 口径：JSON 浮点经 double 反序列化，约 15 位以上有效数字在边界被拒（安全方向过严）
        assertThatIllegalArgumentException()
                .isThrownBy(() -> validateDefault("decimal", "1e15", null))
                .withMessageContaining("NUMERIC(20,6)");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> validateDefault("decimal", "0.1234567", null))
                .withMessageContaining("NUMERIC(20,6)");
        validateDefault("decimal", "12345678901234.5", null);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> validateDefault("date", "\"01.01.2027\"", null))
                .withMessageContaining("ISO-8601");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> validateDefault("enum", "\"c\"", "{\"options\":[\"a\"]}"))
                .withMessageContaining("options");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> validateDefault("enum", "\"a\"", null))
                .withMessageContaining("先行提供");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> validateDefault("boolean", "\"yes\"", null))
                .withMessageContaining("boolean");
    }
}
