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

    /** pluginId 需点分小写且每段以字母开头（P07 ID_PATTERN）：gen.i<issueId hex>。 */
    public static GeneratedPackage generate(String issueId, JsonNode spec) {
        if (!RequirementSchema.validate(spec).isEmpty()) {
            throw new IllegalArgumentException("规格校验未通过，拒绝生成（FR-ISSUE-04）");
        }
        String pluginId = "gen.i" + issueId.replace("iss-", "").replace("-", "");
        String version = "0.1.0";
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

        String close(StringBuilder builder) {
            return builder.append(']').toString();
        }
    }

    private static void appendEntity(JsonNode entity, String pluginId,
                                     Contributions contributions,
                                     Map<String, String> resources) {
        String entityPath = "metadata/entities/" + entity.path("name").asString() + ".json";
        contributions.commaBefore(contributions.entitiesDecl);
        contributions.entitiesDecl.append('"').append(entityPath).append('"');
        contributions.commaBefore(contributions.navigation);
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

    private static String manifestOf(String pluginId, String version, JsonNode spec,
                                     Contributions contributions) {
        return "{\"schemaVersion\":1,\"id\":\"" + pluginId + "\",\"name\":\""
                + spec.path("summary").asString() + "\",\"version\":\"" + version
                + "\",\"capabilityLevel\":1,\"minPlatformVersion\":\"0.1.0\",\"dependencies\":[],"
                + "\"permissions\":" + (spec.has("permissions")
                        ? spec.path("permissions").toString() : "[]")
                + ",\"contributions\":{\"navigation\":" + contributions.close(
                        contributions.navigation)
                + ",\"renderers\":" + contributions.close(contributions.renderers)
                + "},\"resources\":{\"entities\":" + contributions.close(
                        contributions.entitiesDecl)
                + ",\"views\":" + contributions.close(contributions.viewsDecl)
                + ",\"migrations\":[]}}";
    }

    private static byte[] zipOf(Map<String, String> resources) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                ZipOutputStream zos = new ZipOutputStream(out)) {
            for (var entry : resources.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
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
