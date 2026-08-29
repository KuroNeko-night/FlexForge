package com.flexforge.ai.spec;

import com.flexforge.common.PublicApi;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 规格预览（docs/09 P10 验收 4）：从合法 RequirementSpec 确定性地派生
 * 将要生成的插件资源（plugin.json 骨架 + 实体/视图 JSON 文件），开发者据此
 * 在生成前审阅。P11 生成器消费同一派生规则；plugin id 为占位
 * （preview.from-issue，安装性由 P11 生成器落定真实 ID），预览包不可导入。
 */
@PublicApi
public final class SpecPreview {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 预览结果：合法时给资源清单，非法时给错误清单（二者互斥）。 */
    @PublicApi
    public record Preview(boolean valid, Map<String, String> resources, JsonNode errors) {
    }

    private SpecPreview() {
    }

    public static Preview of(JsonNode spec) {
        java.util.List<String> errors = RequirementSchema.validate(spec);
        if (!errors.isEmpty()) {
            return new Preview(false, Map.of(), JSON.valueToTree(errors));
        }
        return new Preview(true, resourcesOf(spec), JSON.valueToTree(java.util.List.of()));
    }

    /**
     * 派生确定性资源清单。预览导航键用可读形态（{@code <实体>.items}）；P11
     * 生成器落定为 {@code <pluginId>.<实体>}（注册表键全局唯一需要插件前缀）
     * ——预览展示与最终注册键形状有意不同（PluginPackageGenerator 侧同口径注记）。
     */
    private static Map<String, String> resourcesOf(JsonNode spec) {
        Map<String, String> resources = new LinkedHashMap<>();
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("schemaVersion", 1);
        manifest.put("id", "preview.from-issue");
        manifest.put("name", spec.path("summary").asString());
        manifest.put("version", "0.1.0");
        manifest.put("capabilityLevel", 1);
        manifest.put("minPlatformVersion", "0.1.0");
        manifest.set("dependencies", JSON.createArrayNode());
        manifest.set("permissions", spec.has("permissions") && !spec.path("permissions").isNull()
                ? spec.path("permissions").deepCopy() : JSON.createArrayNode());
        ObjectNode contributions = manifest.putObject("contributions");
        ArrayNode navigation = contributions.putArray("navigation");
        ArrayNode renderers = contributions.putArray("renderers");
        ObjectNode declared = manifest.putObject("resources");
        ArrayNode entityPaths = declared.putArray("entities");
        ArrayNode viewPaths = declared.putArray("views");
        declared.putArray("migrations");

        JsonNode entities = spec.path("entities");
        for (int i = 0; i < entities.size(); i++) {
            JsonNode entity = entities.get(i);
            String path = "metadata/entities/" + entity.path("name").asString() + ".json";
            entityPaths.add(path);
            navigation.add(entity.path("name").asString() + ".items");
            resources.put(path, pretty(entity));
            renderersOf(entity, renderers);
        }
        JsonNode views = spec.path("views");
        for (int i = 0; i < views.size(); i++) {
            JsonNode view = views.get(i);
            String path = "metadata/views/" + view.path("entity").asString() + "."
                    + view.path("viewType").asString() + ".json";
            viewPaths.add(path);
            resources.put(path, pretty(view));
        }
        resources.put("plugin.json", pretty(manifest));
        return resources;
    }

    private static void renderersOf(JsonNode entity, ArrayNode renderers) {
        JsonNode fields = entity.path("fields");
        for (int i = 0; i < fields.size(); i++) {
            String rendererId = fields.get(i).path("fieldType").asString() + ".default";
            if (!contains(renderers, rendererId)) {
                renderers.add(rendererId);
            }
        }
    }

    private static boolean contains(ArrayNode renderers, String value) {
        for (int i = 0; i < renderers.size(); i++) {
            if (renderers.get(i).asString().equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static String pretty(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? "[]" : node.toString();
    }
}
