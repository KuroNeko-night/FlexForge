package com.flexforge.plugin.application;

import com.flexforge.plugin.domain.PluginValidationException;
import com.flexforge.plugin.domain.ProcessorSpec;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * processors 声明校验（P20 抽自 ManifestValidator，P23 增文件输入模式）：
 * key/kind/entry 白名单与 inputMode 分流——entity（缺省，inputEntity 必填，
 * 拒绝 file 专属字段混入）或 file（accept ⊆ csv/xlsx/txt + maxInputMB 1..5，
 * inputEntity 省略）；Level 2 专属段（S6 双轨）。
 */
final class ProcessorDeclarationValidator {

    private static final Pattern KEY_PATTERN = Pattern.compile(
            "^[a-z][a-z0-9_]*(\\.[a-z0-9_]*)*$");
    private static final Pattern SCRIPT_PATH_PATTERN = Pattern.compile("^scripts/[A-Za-z0-9_.-]+\\.py$");
    private static final Pattern ENTITY_NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*$");

    private ProcessorDeclarationValidator() {
    }

    /** processors 段整体校验（Level 2 专属：缺省/空段时 Level 2 拒、Level 1 合法空）。 */
    static List<ProcessorSpec> processorsOf(JsonNode contributionsNode, int capabilityLevel) {
        JsonNode node = contributionsNode == null ? null : contributionsNode.get("processors");
        boolean declared = node != null && !node.isNull();
        if (!declared) {
            return requireNoneOrReject(capabilityLevel);
        }
        if (capabilityLevel != 2) {
            throw PluginValidationException.invalidManifest(
                    "processors 贡献仅 Level 2 插件可声明（Level 1 为纯声明式）");
        }
        if (!node.isArray()) {
            throw PluginValidationException.invalidManifest("contributions.processors 必须是数组");
        }
        List<ProcessorSpec> result = new ArrayList<>();
        Set<String> seenKeys = new java.util.HashSet<>();
        for (JsonNode item : node) {
            ProcessorSpec spec = processorOf(item);
            if (!seenKeys.add(spec.key())) {
                throw PluginValidationException.invalidManifest("processor key 重复: " + spec.key());
            }
            result.add(spec);
        }
        if (result.isEmpty()) {
            throw PluginValidationException.invalidManifest("Level 2 插件 processors 不能为空");
        }
        return List.copyOf(result);
    }

    private static List<ProcessorSpec> requireNoneOrReject(int capabilityLevel) {
        if (capabilityLevel == 2) {
            throw PluginValidationException.invalidManifest(
                    "Level 2 插件必须声明 contributions.processors（数据处理器）");
        }
        return List.of();
    }

    private record Base(String key, String label, String kind, String entry) {
    }

    private static ProcessorSpec processorOf(JsonNode item) {
        if (item == null || !item.isObject()) {
            throw PluginValidationException.invalidManifest("contributions.processors 含非对象项");
        }
        Base base = baseOf(item);
        // P23 输入模式：file（accept 白名单 + maxInputMB，inputEntity 省略）
        // 或 entity（缺省，inputEntity 必填；不接受 file 专属字段混入）
        String inputMode = item.path("inputMode").asString(ProcessorSpec.MODE_ENTITY);
        if (ProcessorSpec.MODE_FILE.equals(inputMode)) {
            return fileProcessorOf(item, base);
        }
        return entityProcessorOf(item, base, inputMode);
    }

    private static Base baseOf(JsonNode item) {
        String key = text(item, "key");
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw PluginValidationException.invalidManifest("processor key 非法: " + key);
        }
        String label = text(item, "label");
        String kind = text(item, "kind");
        if (!ProcessorSpec.KINDS.contains(kind)) {
            throw PluginValidationException.invalidManifest(
                    "processor kind 非法（允许 python）: " + kind);
        }
        String entry = text(item, "entry");
        if (!SCRIPT_PATH_PATTERN.matcher(entry).matches()) {
            throw PluginValidationException.invalidManifest(
                    "processor entry 须为包内 scripts/*.py 相对路径: " + entry);
        }
        return new Base(key, label, kind, entry);
    }

    /** 实体输入模式声明校验（P20 既有口径 + P23 拒绝 file 专属字段混入）。 */
    private static ProcessorSpec entityProcessorOf(JsonNode item, Base base, String inputMode) {
        if (!ProcessorSpec.MODE_ENTITY.equals(inputMode)) {
            throw PluginValidationException.invalidManifest(
                    "processor inputMode 非法（允许 entity/file）: " + inputMode);
        }
        if (item.has("accept") || item.has("maxInputMB")) {
            throw PluginValidationException.invalidManifest(
                    "entity 输入模式不支持 accept/maxInputMB（file 模式专属）");
        }
        String inputEntity = text(item, "inputEntity");
        if (!ENTITY_NAME_PATTERN.matcher(inputEntity).matches()) {
            throw PluginValidationException.invalidManifest(
                    "processor inputEntity 须为小写下划线实体名: " + inputEntity);
        }
        return new ProcessorSpec(base.key(), base.label(), base.kind(), base.entry(),
                inputEntity, ProcessorSpec.MODE_ENTITY, List.of(), null);
    }

    /** 文件输入模式声明校验（P23，FR-PLUGIN-14，docs/13 §3.5-5）。 */
    private static ProcessorSpec fileProcessorOf(JsonNode item, Base base) {
        List<String> accept = acceptOf(item, base.key());
        int maxMb = maxInputMbOf(item, base.key());
        if (item.has("inputEntity")) {
            throw PluginValidationException.invalidManifest(
                    "file 输入模式不支持 inputEntity（entity 模式专属）: " + base.key());
        }
        return new ProcessorSpec(base.key(), base.label(), base.kind(), base.entry(), "",
                ProcessorSpec.MODE_FILE, accept, maxMb);
    }

    private static List<String> acceptOf(JsonNode item, String key) {
        JsonNode acceptNode = item.get("accept");
        if (acceptNode == null || !acceptNode.isArray() || acceptNode.isEmpty()) {
            throw PluginValidationException.invalidManifest(
                    "file 输入模式必须声明非空 accept 扩展名白名单: " + key);
        }
        List<String> accept = new ArrayList<>();
        for (JsonNode ext : acceptNode) {
            String value = ext.asString("");
            if (!ProcessorSpec.ACCEPTABLE_EXT.contains(value)) {
                throw PluginValidationException.invalidManifest(
                        "accept 只允许 " + ProcessorSpec.ACCEPTABLE_EXT + ": " + value);
            }
            accept.add(value);
        }
        return List.copyOf(accept);
    }

    private static int maxInputMbOf(JsonNode item, String key) {
        JsonNode maxMb = item.get("maxInputMB");
        if (maxMb == null || !maxMb.isInt() || maxMb.asInt() < 1
                || maxMb.asInt() > ProcessorSpec.MAX_INPUT_MB) {
            throw PluginValidationException.invalidManifest(
                    "maxInputMB 必须是 1.." + ProcessorSpec.MAX_INPUT_MB + " 的整数: " + key);
        }
        return maxMb.asInt();
    }

    private static String text(JsonNode item, String field) {
        JsonNode value = item.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw PluginValidationException.invalidManifest("缺少必填字段 " + field);
        }
        return value.asText();
    }
}
