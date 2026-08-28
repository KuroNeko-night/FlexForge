package com.flexforge.plugin.application;

import com.flexforge.plugin.domain.PluginValidationException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * plugin.json 解析（仅结构与 schemaVersion 提取；字段合法性由校验器负责）。
 * 不可解析 JSON 统一 invalid_manifest。
 */
final class ManifestParser {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ManifestParser() {
    }

    static JsonNode parse(byte[] manifestBytes) {
        JsonNode root;
        try {
            root = JSON.readTree(manifestBytes);
        } catch (RuntimeException e) {
            throw PluginValidationException.invalidManifest("plugin.json 不是合法 JSON");
        }
        if (!root.isObject()) {
            throw PluginValidationException.invalidManifest("plugin.json 必须是 JSON 对象");
        }
        return root;
    }

    static int schemaVersionOf(JsonNode root) {
        JsonNode node = root.get("schemaVersion");
        if (node == null || !node.isInt()) {
            throw PluginValidationException.invalidManifest("缺少整数型 schemaVersion 字段");
        }
        return node.intValue();
    }
}
