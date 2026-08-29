package com.flexforge.ai.spec;

import com.flexforge.common.PublicApi;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Level 1 插件包生成器（docs/09 P11、FR-ISSUE-05）：从确认后的合法规格
 * 确定性生成可导入插件包（与 SpecPreview 同一派生规则 + 真实插件 ID/版本）。
 * 输出经标准 P07 校验链导入（同权），无任何骨架旁路；生成是确定性转换，
 * 不调用模型（模型只用于澄清，docs/03 §6）。
 */
@PublicApi
public final class PluginPackageGenerator {

    private static final JsonMapper JSON = JsonMapper.builder().build();


    private PluginPackageGenerator() {
    }

    /** 生成的包描述（导入结果与展示用）。 */
    @PublicApi
    public record GeneratedPackage(byte[] zip, String pluginId, String version) {
    }

    /**
     * pluginId=gen.i<issueId hex>（P07 ID_PATTERN）；version=0.1.<specRevision>——
     * 版本随规格修订递增（同 Issue 迭代再生成不撞"同版本不可变"，FR-ISSUE-06 回路）。
     */
    public static GeneratedPackage generate(String issueId, int specRevision, JsonNode spec) {
        if (!RequirementSchema.validate(spec).isEmpty()) {
            throw new IllegalArgumentException("规格校验未通过，拒绝生成（FR-ISSUE-04）");
        }
        String pluginId = "gen.i" + issueId.replace("iss-", "").replace("-", "");
        String version = "0.1." + specRevision;
        Map<String, String> resources = derivedResources(pluginId, version, spec);
        return new GeneratedPackage(zipOf(resources), pluginId, version);
    }

    private static Map<String, String> derivedResources(String pluginId, String version,
                                                        JsonNode spec) {
        Map<String, String> resources = new LinkedHashMap<>();
        Contributions contributions = new Contributions();
        JsonNode entities = spec.path("entities");
        for (int i = 0; i < entities.size(); i++) {
            appendEntity(entities.get(i), pluginId, contributions, resources);
        }
        JsonNode views = spec.path("views");
        for (int i = 0; i < views.size(); i++) {
            appendView(views.get(i), contributions, resources);
        }
        resources.put("plugin.json", manifestOf(pluginId, version, spec, contributions));
        return resources;
    }

    /** 派生过程中的声明累积（entities/views 声明与 navigation/renderers 贡献）。 */
    private static final class Contributions {
        final StringBuilder entitiesDecl = new StringBuilder("[");
        final StringBuilder viewsDecl = new StringBuilder("[");
        final StringBuilder navigation = new StringBuilder("[");
        final StringBuilder renderers = new StringBuilder("[");

        void commaBefore(StringBuilder builder) {
            if (builder.length() > 1) {
                builder.append(',');
            }
        }
    }

    private static void appendEntity(JsonNode entity, String pluginId,
                                     Contributions contributions,
                                     Map<String, String> resources) {
        String entityPath = "metadata/entities/" + entity.path("name").asString() + ".json";
        contributions.commaBefore(contributions.entitiesDecl);
        contributions.entitiesDecl.append('"').append(entityPath).append('"');
        contributions.commaBefore(contributions.navigation);
        // 注册表键全局共享：导航键带 pluginId 前缀防跨插件实体同名冲突
        contributions.navigation.append('"').append(pluginId).append('.')
                .append(entity.path("name").asString()).append('"');
        JsonNode fields = entity.path("fields");
        for (int i = 0; i < fields.size(); i++) {
            String rendererId = fields.get(i).path("fieldType").asString() + ".default";
            if (contributions.renderers.indexOf("\"" + rendererId + "\"") < 0) {
                contributions.commaBefore(contributions.renderers);
                contributions.renderers.append('"').append(rendererId).append('"');
            }
        }
        resources.put(entityPath, entity.toString());
    }

    private static void appendView(JsonNode view, Contributions contributions,
                                   Map<String, String> resources) {
        String viewPath = "metadata/views/" + view.path("entity").asString() + "."
                + view.path("viewType").asString() + ".json";
        contributions.commaBefore(contributions.viewsDecl);
        contributions.viewsDecl.append('"').append(viewPath).append('"');
        resources.put(viewPath, view.toString());
    }

    /** manifest 经 ObjectNode 构造（JSON 安全：summary 含引号/反斜杠不再破坏结构）。 */
    private static String manifestOf(String pluginId, String version, JsonNode spec,
                                     Contributions contributions) {
        var manifest = JSON.createObjectNode();
        manifest.put("schemaVersion", 1);
        manifest.put("id", pluginId);
        manifest.put("name", spec.path("summary").asString());
        manifest.put("version", version);
        manifest.put("capabilityLevel", 1);
        manifest.put("minPlatformVersion", "0.1.0");
        manifest.putArray("dependencies");
        manifest.set("permissions", spec.has("permissions") && !spec.path("permissions").isNull()
                ? spec.path("permissions").deepCopy() : JSON.createArrayNode());
        var contributionsNode = manifest.putObject("contributions");
        contributionsNode.set("navigation", arrayNodeOf(contributions.navigation));
        contributionsNode.set("renderers", arrayNodeOf(contributions.renderers));
        var resourcesNode = manifest.putObject("resources");
        resourcesNode.set("entities", arrayNodeOf(contributions.entitiesDecl));
        resourcesNode.set("views", arrayNodeOf(contributions.viewsDecl));
        resourcesNode.putArray("migrations");
        return manifest.toString();
    }

    /** 已拼接的 JSON 数组文本（元素均为合法 JSON 字面量）解析回数组节点。 */
    private static JsonNode arrayNodeOf(StringBuilder builder) {
        return JSON.readTree((builder + "]").getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] zipOf(Map<String, String> resources) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                ZipOutputStream zos = new ZipOutputStream(out)) {
            for (var entry : resources.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTime(0); // 字节级确定性：同规格重复生成产出同 zip
                zos.putNextEntry(zipEntry);
                zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            zos.close();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("生成插件包失败", e);
        }
    }
}
