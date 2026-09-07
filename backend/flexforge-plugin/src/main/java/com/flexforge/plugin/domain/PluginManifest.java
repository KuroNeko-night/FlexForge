package com.flexforge.plugin.domain;

import com.flexforge.common.PublicApi;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 解析后的 plugin.json（docs/08 §4 Level 1 格式）。raw 保留原始 JSON
 * （不可变落库 plugin_version.manifest_json）；结构化字段由校验器保证合法性。
 */
@PublicApi
public record PluginManifest(
        int schemaVersion,
        String id,
        String name,
        String version,
        int capabilityLevel,
        String minPlatformVersion,
        List<DependencySpec> dependencies,
        List<String> permissions,
        Map<String, List<String>> contributions,
        List<ThemeAssetSpec> themeAssets,
        List<ProcessorSpec> processors,
        Resources resources,
        JsonNode raw) {

    /** 包内资源声明：路径必须是包内相对路径，由 ArchiveInspector 逐一核对存在性。 */
    @PublicApi
    public record Resources(List<String> entities, List<String> views, List<String> migrations) {

        public Resources {
            entities = entities == null ? List.of() : List.copyOf(entities);
            views = views == null ? List.of() : List.copyOf(views);
            migrations = migrations == null ? List.of() : List.copyOf(migrations);
        }
    }

    public PluginManifest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(minPlatformVersion, "minPlatformVersion");
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        contributions = contributions == null ? Map.of() : Map.copyOf(contributions);
        themeAssets = themeAssets == null ? List.of() : List.copyOf(themeAssets);
        processors = processors == null ? List.of() : List.copyOf(processors);
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(raw, "raw");
    }
}
