package com.flexforge.plugin.application;

import com.flexforge.plugin.domain.PluginManifest;
import com.flexforge.plugin.domain.PluginValidationException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** plugin.json 校验（RB-PLUGIN-VALID：缺失字段/越权 renderer/登记册外贡献键/等级）。 */
class ManifestValidatorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ManifestValidator validator = new ManifestValidator();

    private static String manifest() {
        return "{\"schemaVersion\":1,\"id\":\"example.inventory\",\"name\":\"库存示例\","
                + "\"version\":\"0.1.0\",\"capabilityLevel\":1,\"minPlatformVersion\":\"0.1.0\","
                + "\"dependencies\":[],\"contributions\":{\"navigation\":[\"inventory.items\"],"
                + "\"renderers\":[\"enum.default\"]},"
                + "\"resources\":{\"entities\":[\"metadata/entities/item.json\"],"
                + "\"views\":[],\"migrations\":[\"migrations/V001__inventory_item.sql\"]}}";
    }

    private PluginValidationException failing(String json) {
        try {
            validate(json);
        } catch (PluginValidationException e) {
            return e;
        }
        throw new AssertionError("应当被拒绝: " + json);
    }

    private PluginManifest validate(String json) {
        JsonNode root = JSON.readTree(json.getBytes());
        return validator.validate(root, root.get("schemaVersion").intValue());
    }

    @Test
    void validManifestParsedWithStructuredFields() {
        PluginManifest manifest = validate(manifest());
        assertThat(manifest.id()).isEqualTo("example.inventory");
        assertThat(manifest.capabilityLevel()).isEqualTo(1);
        assertThat(manifest.contributions()).containsKeys("navigation", "renderers");
        assertThat(manifest.resources().migrations())
                .containsExactly("migrations/V001__inventory_item.sql");
    }

    @Test
    void unknownSchemaVersionRejectedWithDedicatedCode() {
        String json = manifest().replace("\"schemaVersion\":1", "\"schemaVersion\":2");
        assertThatThrownBy(() -> {
            JsonNode root = JSON.readTree(json.getBytes());
            validator.validate(root, 2);
        }).isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("schemaVersion")
                .extracting("code").isEqualTo("unsupported_schema_version");
    }

    @Test
    void missingRequiredFieldsRejected() {
        assertThat(failing(manifest().replace("\"id\":\"example.inventory\",", ""))
                .getMessage()).contains("id");
        assertThat(failing(manifest().replace("\"version\":\"0.1.0\",", ""))
                .getMessage()).contains("version");
        assertThat(failing(manifest().replace("\"minPlatformVersion\":\"0.1.0\",", ""))
                .getMessage()).contains("minPlatformVersion");
        assertThat(failing(manifest().replace("\"name\":\"库存示例\",", ""))
                .getMessage()).contains("name");
    }

    @Test
    void onlyLevelOneSupported() {
        assertThat(failing(manifest().replace("\"capabilityLevel\":1", "\"capabilityLevel\":2"))
                .getMessage()).contains("capabilityLevel");
        assertThat(failing(manifest().replace("\"capabilityLevel\":1,", ""))
                .getMessage()).contains("capabilityLevel");
    }

    @Test
    void contributionKeysAndRendererIdsCheckedAgainstRegistry() {
        assertThat(failing(manifest().replace("\"navigation\"", "\"menus\""))
                .getMessage()).contains("登记册外");
        assertThat(failing(manifest().replace("\"enum.default\"", "\"table.default\""))
                .getMessage()).contains("rendererId 不在平台内置白名单");
        assertThat(failing(manifest().replace("\"enum.default\"", "\"custom.renderer\""))
                .getMessage()).contains("rendererId 不在平台内置白名单");
    }

    @Test
    void dependencyDeclarationsValidated() {
        assertThat(failing(manifest().replace("\"dependencies\":[]",
                        "\"dependencies\":[{\"pluginId\":\"example.inventory\",\"versionRange\":\"*\"}]"))
                .getMessage()).contains("依赖自身");
        assertThat(failing(manifest().replace("\"dependencies\":[]",
                        "\"dependencies\":[{\"pluginId\":\"vendor.thing\",\"versionRange\":\">=1.0\"}]"))
                .getMessage()).contains("versionRange");
        assertThat(failing(manifest().replace("\"dependencies\":[]",
                        "\"dependencies\":[{\"pluginId\":\"Bad.Id\",\"versionRange\":\"*\"}]"))
                .getMessage()).contains("pluginId 非法");
    }

    @Test
    void resourcePathsMustUseDeclaredAreas() {
        assertThat(failing(manifest().replace("metadata/entities/item.json", "entities/item.json"))
                .getMessage()).contains("resources.entities");
    }
}
