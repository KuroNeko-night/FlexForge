package com.flexforge.plugin.application;

import com.flexforge.common.api.ErrorCodes;
import com.flexforge.meta.domain.FieldTypeRegistry;
import com.flexforge.plugin.domain.DependencySpec;
import com.flexforge.plugin.domain.PluginManifest;
import com.flexforge.plugin.domain.PluginValidationException;
import com.flexforge.plugin.domain.ThemeAssetSpec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * plugin.json 校验器（schemaVersion 分派，docs/09 P07）：
 * 未知 schemaVersion → unsupported_schema_version；V1 校验必填字段、格式、
 * 能力等级（MVP 仅 Level 1）、贡献键与 renderer ID 对登记册/平台白名单校验、
 * 依赖声明结构。产出结构化 {@link PluginManifest}。
 */
@Component
public class ManifestValidator {

    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$");
    private static final Pattern VERSION_PATTERN = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");
    private static final Pattern RANGE_PATTERN = Pattern.compile("^(\\*|\\^?\\d+\\.\\d+\\.\\d+)$");
    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z0-9_]*)*$");
    private static final Pattern ASSET_PATH_PATTERN =
            Pattern.compile("^assets/[a-z0-9_./-]+\\.(png|svg|webp|css|json)$");
    /** themeAssets 为对象数组，单独解析；字符串贡献键仅 navigation/renderers。 */
    private static final Set<String> STRING_CONTRIBUTION_KEYS = Set.of("navigation", "renderers");
    private static final Set<String> CONTRIBUTION_KEYS = Set.of("navigation", "renderers", "themeAssets");

    public PluginManifest validate(JsonNode root, int schemaVersion) {
        if (schemaVersion != 1) {
            throw PluginValidationException.unsupportedSchemaVersion(schemaVersion);
        }
        return validateV1(root);
    }

    private PluginManifest validateV1(JsonNode root) {
        String id = requiredText(root, "id");
        if (!ID_PATTERN.matcher(id).matches()) {
            throw PluginValidationException.invalidManifest("id 须为小写点分标识（如 example.inventory）: " + id);
        }
        String name = requiredText(root, "name");
        String version = requiredText(root, "version");
        if (!VERSION_PATTERN.matcher(version).matches()) {
            throw PluginValidationException.invalidManifest("version 须为 X.Y.Z 语义化版本: " + version);
        }
        requireLevel1(root, id);
        String minPlatform = requiredText(root, "minPlatformVersion");
        if (!VERSION_PATTERN.matcher(minPlatform).matches()) {
            throw PluginValidationException.invalidManifest("minPlatformVersion 须为 X.Y.Z: " + minPlatform);
        }
        List<DependencySpec> dependencies = dependenciesOf(root, id);
        List<String> permissions = stringList(root.get("permissions"), "permissions", KEY_PATTERN);
        JsonNode contributionsNode = root.get("contributions");
        List<ThemeAssetSpec> themeAssets = themeAssetsOf(contributionsNode);
        Map<String, List<String>> contributions = contributionsOf(contributionsNode);
        PluginManifest.Resources resources = resourcesOf(root);
        return new PluginManifest(1, id, name, version, 1, minPlatform,
                dependencies, permissions, contributions, themeAssets, resources, root);
    }

    private static void requireLevel1(JsonNode root, String id) {
        JsonNode level = root.get("capabilityLevel");
        if (level == null || !level.isInt() || level.intValue() != 1) {
            throw PluginValidationException.invalidManifest(
                    "capabilityLevel 必须为 1（MVP 仅支持 Level 1 声明式插件）: " + id);
        }
    }

    private static List<DependencySpec> dependenciesOf(JsonNode root, String selfId) {
        JsonNode node = root.get("dependencies");
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw PluginValidationException.invalidManifest("dependencies 必须是数组");
        }
        List<DependencySpec> result = new ArrayList<>();
        for (JsonNode item : node) {
            String pluginId = fieldText(item, "pluginId");
            if (!ID_PATTERN.matcher(pluginId).matches()) {
                throw PluginValidationException.invalidManifest("依赖 pluginId 非法: " + pluginId);
            }
            if (pluginId.equals(selfId)) {
                throw PluginValidationException.invalidManifest("不允许依赖自身: " + pluginId);
            }
            String range = fieldText(item, "versionRange");
            if (!RANGE_PATTERN.matcher(range).matches()) {
                throw PluginValidationException.invalidManifest(
                        "依赖 versionRange 须为 X.Y.Z / ^X.Y.Z / *: " + pluginId + " " + range);
            }
            result.add(new DependencySpec(pluginId, range));
        }
        return List.copyOf(result);
    }

    /** themeAssets 贡献（对象数组，登记册 §2.2）：key/kind/path 必填，scope 可选；key 不得重复。 */
    private static List<ThemeAssetSpec> themeAssetsOf(JsonNode contributionsNode) {
        if (contributionsNode == null || !contributionsNode.isObject()) {
            return List.of();
        }
        JsonNode node = contributionsNode.get("themeAssets");
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw PluginValidationException.invalidManifest("contributions.themeAssets 必须是数组");
        }
        List<ThemeAssetSpec> result = new ArrayList<>();
        for (JsonNode item : node) {
            ThemeAssetSpec spec = themeAssetOf(item);
            if (result.contains(spec)) {
                throw PluginValidationException.invalidManifest("themeAsset key 重复: " + spec.key());
            }
            result.add(spec);
        }
        return List.copyOf(result);
    }

    private static ThemeAssetSpec themeAssetOf(JsonNode item) {
        if (item == null || !item.isObject()) {
            throw PluginValidationException.invalidManifest("contributions.themeAssets 含非对象项");
        }
        String key = fieldText(item, "key");
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw PluginValidationException.invalidManifest("themeAsset key 非法: " + key);
        }
        String path = fieldText(item, "path");
        if (!ASSET_PATH_PATTERN.matcher(path).matches()) {
            throw PluginValidationException.invalidManifest(
                    "themeAsset path 须为包内 assets/ 白名单扩展名相对路径: " + path);
        }
        JsonNode scope = item.get("scope");
        if (scope != null && scope.isTextual()
                && !KEY_PATTERN.matcher(scope.asText()).matches()) {
            throw PluginValidationException.invalidManifest("themeAsset scope 非法: " + scope);
        }
        return new ThemeAssetSpec(key, fieldText(item, "kind"), path,
                scope == null || scope.isNull() ? null : scope.asText());
    }

    private static Map<String, List<String>> contributionsOf(JsonNode contributionsNode) {
        if (contributionsNode == null || contributionsNode.isNull()) {
            return Map.of();
        }
        if (!contributionsNode.isObject()) {
            throw PluginValidationException.invalidManifest("contributions 必须是对象");
        }
        requireKnownContributionKeys(contributionsNode);
        Map<String, List<String>> result = new HashMap<>();
        for (String key : STRING_CONTRIBUTION_KEYS) {
            JsonNode value = contributionsNode.get(key);
            if (value == null) {
                continue;
            }
            if (value.isNull()) {
                throw PluginValidationException.invalidManifest(
                        "contributions." + key + " 为 null（缺省请省略该键）");
            }
            result.put(key, stringList(value, "contributions." + key, KEY_PATTERN));
        }
        requireBuiltInRenderers(result.getOrDefault("renderers", List.of()));
        return result;
    }

    private static void requireKnownContributionKeys(JsonNode contributionsNode) {
        for (String key : contributionsNode.propertyNames()) {
            if (!CONTRIBUTION_KEYS.contains(key)) {
                throw new PluginValidationException(ErrorCodes.VALIDATION_ERROR,
                        "contributions 含登记册外的贡献类型（允许: " + CONTRIBUTION_KEYS + "）: " + key);
            }
        }
    }

    private static void requireBuiltInRenderers(List<String> rendererIds) {
        for (String rendererId : rendererIds) {
            if (!FieldTypeRegistry.isBuiltInRendererId(rendererId)) {
                throw new PluginValidationException(ErrorCodes.VALIDATION_ERROR,
                        "rendererId 不在平台内置白名单: " + rendererId);
            }
        }
    }

    private static PluginManifest.Resources resourcesOf(JsonNode root) {
        JsonNode node = root.get("resources");
        if (node == null || !node.isObject()) {
            throw PluginValidationException.invalidManifest("缺少 resources 对象");
        }
        return new PluginManifest.Resources(
                resourceList(node.get("entities"), "resources.entities", "metadata/"),
                resourceList(node.get("views"), "resources.views", "metadata/"),
                resourceList(node.get("migrations"), "resources.migrations", "migrations/"));
    }

    private static List<String> resourceList(JsonNode node, String label, String prefix) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw PluginValidationException.invalidManifest(label + " 必须是数组");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || !item.asText().startsWith(prefix)) {
                throw PluginValidationException.invalidManifest(
                        label + " 路径必须是包内 " + prefix + " 相对路径: " + item);
            }
            result.add(item.asText());
        }
        return List.copyOf(result);
    }

    private static List<String> stringList(JsonNode node, String label, Pattern pattern) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw PluginValidationException.invalidManifest(label + " 必须是数组");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || !pattern.matcher(item.asText()).matches()) {
                throw PluginValidationException.invalidManifest(label + " 含非法项: " + item);
            }
            result.add(item.asText());
        }
        return List.copyOf(result);
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw PluginValidationException.invalidManifest("缺少必填字段 " + field);
        }
        return node.asText();
    }

    private static String fieldText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw PluginValidationException.invalidManifest("缺少必填字段 " + field);
        }
        return value.asText();
    }
}
