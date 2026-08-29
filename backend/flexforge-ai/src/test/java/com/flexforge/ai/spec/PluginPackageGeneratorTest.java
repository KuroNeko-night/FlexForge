package com.flexforge.ai.spec;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 生成器：合法规格 → 可导入 Level 1 包（确定性、内置 renderer、非法规格拒绝）。 */
class PluginPackageGeneratorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String VALID = """
            {"schemaVersion":1,"summary":"生成示例","entities":[
            {"name":"gen_item","displayName":"生成项","fields":[
            {"name":"name","displayName":"名称","fieldType":"text","required":true},
            {"name":"qty","displayName":"数量","fieldType":"integer",
             "validation":{"min":0}}]}],
            "views":[{"entity":"gen_item","viewType":"list","name":"列表",
            "columns":[{"field":"name"}]}],
            "permissions":["gen.read"],"acceptance":["可查询"]}
            """;

    @Test
    void validSpecGeneratesPackageWithDeterministicId() throws Exception {
        PluginPackageGenerator.GeneratedPackage pkg =
                PluginPackageGenerator.generate("iss-abc123", 1, JSON.readTree(VALID));
        assertThat(pkg.pluginId()).isEqualTo("gen.iabc123");
        assertThat(pkg.version()).isEqualTo("0.1.1");
        assertThat(PluginPackageGenerator.generate("iss-abc123", 1, JSON.readTree(VALID)).zip())
                .isEqualTo(pkg.zip());

        Map<String, String> entries = unzip(pkg.zip());
        assertThat(entries).containsKeys("plugin.json", "metadata/entities/gen_item.json",
                "metadata/views/gen_item.list.json");
        JsonNode manifest = JSON.readTree(entries.get("plugin.json"));
        assertThat(manifest.path("id").asString()).isEqualTo("gen.iabc123");
        assertThat(manifest.path("contributions").path("navigation").get(0).asString())
                .isEqualTo("gen.iabc123.gen_item");
        // renderer 只能是内置 ID（越权 renderer 无法进入生成包）
        assertThat(manifest.path("contributions").path("renderers").toString())
                .contains("text.default").contains("integer.default").doesNotContain("custom");
        assertThat(manifest.path("resources").path("migrations").isEmpty()).isTrue();
    }

    @Test
    void invalidSpecRejected() {
        JsonNode invalid = JSON.readTree("""
                {"schemaVersion":1,"summary":"s","entities":[
                {"name":"x_item","displayName":"X","fields":[
                {"name":"n","displayName":"N","fieldType":"text"}]}],"acceptance":[]}
                """);
        assertThatThrownBy(() -> PluginPackageGenerator.generate("iss-x", 1, invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("拒绝生成");
    }

    private static Map<String, String> unzip(byte[] zip) throws Exception {
        Map<String, String> entries = new java.util.LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zis.readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
