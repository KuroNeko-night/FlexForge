package com.flexforge.ai.spec;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 预览派生（docs/09 P10 验收 4）：合法规格 → 确定性插件资源清单。 */
class SpecPreviewTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String VALID = """
            {
              "schemaVersion": 1,
              "summary": "库存管理",
              "entities": [{
                "name": "inv_item",
                "displayName": "库存项",
                "fields": [
                  {"name": "sku", "displayName": "编码", "fieldType": "text", "required": true},
                  {"name": "qty", "displayName": "数量", "fieldType": "integer"}
                ]
              }],
              "views": [{"entity": "inv_item", "viewType": "form", "name": "表单",
                "columns": [{"field": "sku"}]}],
              "permissions": ["inv.read"],
              "acceptance": ["可查询"]
            }
            """;

    @Test
    void validSpecDerivesResources() {
        SpecPreview.Preview preview = SpecPreview.of(JSON.readTree(VALID));
        assertThat(preview.valid()).isTrue();
        assertThat(preview.resources()).containsKeys("plugin.json",
                "metadata/entities/inv_item.json", "metadata/views/inv_item.form.json");
        String manifest = preview.resources().get("plugin.json");
        assertThat(manifest).contains("\"summary-name\"".replace("summary-name", "库存管理"))
                .contains("inv_item.items").contains("text.default").contains("integer.default")
                .contains("metadata/entities/inv_item.json");
    }

    @Test
    void invalidSpecReturnsErrorsWithoutResources() {
        SpecPreview.Preview preview = SpecPreview.of(JSON.readTree("{}"));
        assertThat(preview.valid()).isFalse();
        assertThat(preview.resources()).isEmpty();
        assertThat(preview.errors().size()).isPositive();
    }
}
