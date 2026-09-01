package com.flexforge.plugin.application;

import com.flexforge.common.api.ErrorCodes;
import com.flexforge.common.contract.NavigationContribution;
import com.flexforge.common.contract.ThemeAssetContribution;
import com.flexforge.plugin.domain.LifecycleRepository;
import com.flexforge.meta.domain.FieldTypeRegistry;
import com.flexforge.meta.domain.ViewRules;
import com.flexforge.meta.domain.ViewType;
import com.flexforge.plugin.domain.PluginValidationException;
import com.flexforge.plugin.domain.PluginVersionRecord;
import com.flexforge.plugin.domain.ThemeAssetSpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 贡献载荷工厂（纯函数）：从 plugin_version 记录构造 navigation/renderer 注册载荷。
 * manifest 的 contributions 只携带 key（docs/08 §4），登记册 §2.2 契约的其余字段由
 * 运行时派生：title=插件名，route 优先指向首个注册实体的动态列表页（前端
 * router data/:entity），无实体时退回按 key 展开的路径；order 默认 100。
 */
final class PluginContributionFactory {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PluginContributionFactory() {
    }

    /** resource_payloads JSON（DB 读取；值为 JSON 字符串需解包）解析为实体规格节点。 */
    static JsonNode parseEntities(String resourcePayloadsJson) {
        if (resourcePayloadsJson == null) {
            return JSON.readTree("{}");
        }
        return JSON.readTree(resourcePayloadsJson.getBytes(StandardCharsets.UTF_8));
    }

    /** 单个实体规格：resource_payloads 值经 Map 序列化双重编码，textual 时解包。 */
    static JsonNode entitySpec(JsonNode entities, String path) {
        JsonNode spec = entities.get(path);
        if (spec != null && spec.isTextual()) {
            return JSON.readTree(spec.asText().getBytes(StandardCharsets.UTF_8));
        }
        return spec;
    }

    /** 实体路径前缀判别（registerMetadata 分流实体/视图）。 */
    static boolean isEntityPath(String path) {
        return path.startsWith("metadata/entities/");
    }

    /** 视图规格（metadata/views/*）：{entity,viewType,name,columns,filters?}。 */
    static List<JsonNode> viewSpecsOf(JsonNode resourcePayloads) {
        List<JsonNode> result = new ArrayList<>();
        for (String path : resourcePayloads.propertyNames()) {
            if (path.startsWith("metadata/views/")) {
                JsonNode spec = entitySpec(resourcePayloads, path);
                if (spec != null) {
                    result.add(spec);
                }
            }
        }
        return result;
    }

    /** 包内实体名 → 字段名集合（视图字段白名单校验用）。 */
    static Map<String, java.util.Set<String>> entityFieldNamesOf(JsonNode payloads) {
        Map<String, java.util.Set<String>> result = new HashMap<>();
        for (String path : payloads.propertyNames()) {
            if (!isEntityPath(path)) {
                continue;
            }
            JsonNode spec = entitySpec(payloads, path);
            java.util.Set<String> fields = new java.util.HashSet<>();
            for (JsonNode field : spec.path("fields")) {
                fields.add(field.path("name").asString());
            }
            result.put(spec.path("name").asString(), fields);
        }
        return result;
    }

    /** 包内实体名 → enum 字段名集合（kanban groupBy 校验用，P17）。 */
    static Map<String, java.util.Set<String>> entityEnumFieldNamesOf(JsonNode payloads) {
        Map<String, java.util.Set<String>> result = new HashMap<>();
        for (String path : payloads.propertyNames()) {
            if (!isEntityPath(path)) {
                continue;
            }
            JsonNode spec = entitySpec(payloads, path);
            java.util.Set<String> fields = new java.util.HashSet<>();
            for (JsonNode field : spec.path("fields")) {
                if (FieldTypeRegistry.require(field.path("fieldType").asString())
                        == FieldTypeRegistry.FieldType.ENUM) {
                    fields.add(field.path("name").asString());
                }
            }
            result.put(spec.path("name").asString(), fields);
        }
        return result;
    }

    /** 首个注册实体名（navigation route 派生用）；无实体返回 null（仅计实体路径）。 */
    static String firstEntityNameOf(JsonNode entities) {
        for (String path : entities.propertyNames()) {
            if (!isEntityPath(path)) {
                continue;
            }
            JsonNode spec = entitySpec(entities, path);
            if (spec != null && spec.has("name")) {
                return spec.get("name").asString();
            }
        }
        return null;
    }

    static List<String> navigationKeysOf(PluginVersionRecord version) {
        return manifestContributions(version).getOrDefault("navigation", List.of());
    }

    static List<String> rendererIdsOf(PluginVersionRecord version) {
        return manifestContributions(version).getOrDefault("renderers", List.of());
    }

    /** 迁移执行顺序（docs/08 §3.3：按 manifest resources.migrations 声明顺序）。 */
    static List<String> migrationOrderOf(PluginVersionRecord version) {
        JsonNode list = JSON.readTree(version.manifestJson())
                .path("resources").path("migrations");
        List<String> result = new ArrayList<>();
        if (list.isArray()) {
            for (int i = 0; i < list.size(); i++) {
                result.add(list.get(i).asString());
            }
        }
        return List.copyOf(result);
    }

    /** themeAssets 贡献（对象数组，登记册 §2.2）：激活期从 manifestJson 原始解析。 */
    static List<ThemeAssetSpec> themeAssetsOf(PluginVersionRecord version) {
        JsonNode node = JSON.readTree(version.manifestJson())
                .path("contributions").path("themeAssets");
        if (!node.isArray()) {
            return List.of();
        }
        List<ThemeAssetSpec> result = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            JsonNode scope = item.get("scope");
            result.add(new ThemeAssetSpec(item.path("key").asString(),
                    item.path("kind").asString(), item.path("path").asString(),
                    scope == null || scope.isNull() ? null : scope.asText()));
        }
        return List.copyOf(result);
    }

    /** 内存注册对象（§2.2 契约类型）。 */
    static ThemeAssetContribution themeAssetContribution(ThemeAssetSpec spec) {
        return new ThemeAssetContribution(spec.key(), spec.kind(), spec.path(), spec.scope());
    }

    /** plugin_registration 持久化载荷（§2.2 契约完整字段）。 */
    static String themeAssetPayload(ThemeAssetSpec spec) {
        return JSON.writeValueAsString(themeAssetContribution(spec));
    }

    /** 内存注册对象（MenuService 消费 §2.2 契约类型）。 */
    static NavigationContribution navigationContribution(String key, PluginVersionRecord version,
                                                          JsonNode entities) {
        String firstEntity = firstEntityNameOf(entities);
        String route = firstEntity == null ? "/" + key.replace('.', '/') : "/data/" + firstEntity;
        return new NavigationContribution(key, displayNameOf(version), route, null, 100, null);
    }

    /** plugin_registration 持久化载荷（§2.2 契约完整字段）。 */
    static String navigationPayload(String key, PluginVersionRecord version, JsonNode entities) {
        return JSON.writeValueAsString(navigationContribution(key, version, entities));
    }

    /** 实体规格 → meta_field 写入参数。 */
    static List<LifecycleRepository.FieldSpec> fieldSpecsOf(JsonNode entitySpec) {
        JsonNode fields = entitySpec.path("fields");
        List<LifecycleRepository.FieldSpec> result = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            JsonNode field = fields.get(i);
            result.add(new LifecycleRepository.FieldSpec(
                    field.get("name").asString(),
                    field.path("displayName").asString(field.get("name").asString()),
                    field.get("fieldType").asString(),
                    field.path("required").asBoolean(false),
                    field.has("validation") ? field.get("validation").toString() : null,
                    field.has("defaultValue") ? field.get("defaultValue").toString() : null,
                    field.path("position").asInt(0)));
        }
        return result;
    }

    private static Map<String, List<String>> manifestContributions(PluginVersionRecord version) {
        JsonNode manifest = JSON.readTree(version.manifestJson());
        JsonNode contributions = manifest.get("contributions");
        if (contributions == null || !contributions.isObject()) {
            return Map.of();
        }
        Map<String, List<String>> result = new HashMap<>();
        for (String key : contributions.propertyNames()) {
            if ("themeAssets".equals(key)) {
                continue; // 对象数组贡献，经 themeAssetsOf 单独解析
            }
            JsonNode array = contributions.get(key);
            if (array != null && array.isArray()) {
                List<String> values = new ArrayList<>();
                for (int i = 0; i < array.size(); i++) {
                    values.add(array.get(i).asString());
                }
                result.put(key, List.copyOf(values));
            }
        }
        return result;
    }

    private static String displayNameOf(PluginVersionRecord version) {
        JsonNode manifest = JSON.readTree(version.manifestJson());
        JsonNode name = manifest.get("name");
        return name == null ? version.pluginId() : name.asText();
    }

    /**
     * 单视图规格校验并转 upsert 载荷（P17 扩 kanban：groupBy 必填且为 enum 字段）。
     * 非法声明抛 PluginValidationException——激活失败于 REGISTER 并留痕。
     */
    static LifecycleRepository.ViewUpsert viewUpsertOf(JsonNode view,
                                                       Map<String, java.util.Set<String>> entityFields,
                                                       Map<String, java.util.Set<String>> enumFields) {
        String viewType = view.path("viewType").asString();
        String entityName = view.path("entity").asString();
        if (!List.of("list", "form", "kanban").contains(viewType)) {
            throw new PluginValidationException(ErrorCodes.VALIDATION_ERROR,
                    "视图 viewType 非法（允许 list/form/kanban）: " + viewType);
        }
        java.util.Set<String> fieldNames = entityFields.get(entityName);
        if (fieldNames == null) {
            throw new PluginValidationException(ErrorCodes.VALIDATION_ERROR,
                    "视图引用了包外实体: " + entityName);
        }
        String viewName = view.path("name").asString(viewType);
        String groupBy = view.path("groupBy").asString(null);
        ViewRules.validate(ViewType.fromName(viewType), viewName,
                view.get("columns"), view.get("filters"), fieldNames);
        if ("kanban".equals(viewType)) {
            ViewRules.validateKanban(groupBy, fieldNames,
                    enumFields.getOrDefault(entityName, java.util.Set.of()));
        }
        return new LifecycleRepository.ViewUpsert(viewType, viewName,
                view.has("columns") ? view.get("columns").toString() : null,
                view.has("filters") ? view.get("filters").toString() : null,
                "kanban".equals(viewType) ? groupBy : null);
    }
}
