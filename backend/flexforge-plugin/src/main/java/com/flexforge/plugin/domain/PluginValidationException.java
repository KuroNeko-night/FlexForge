package com.flexforge.plugin.domain;

import com.flexforge.common.PublicApi;
import com.flexforge.common.api.ErrorCodes;

/**
 * 插件包校验失败（携带稳定错误码，docs/08 §7）：invalid_manifest /
 * unsupported_schema_version / dependency_missing / validation_error。
 * 统一错误装配按 code 映射 400，消息面向管理员可诊断。
 */
@PublicApi
public class PluginValidationException extends RuntimeException {

    private final String code;

    public PluginValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static PluginValidationException invalidManifest(String message) {
        return new PluginValidationException(ErrorCodes.INVALID_MANIFEST, message);
    }

    public static PluginValidationException unsupportedSchemaVersion(int found) {
        return new PluginValidationException(ErrorCodes.UNSUPPORTED_SCHEMA_VERSION,
                "不支持的 plugin.json schemaVersion: " + found + "（当前支持: 1）");
    }

    public String code() {
        return code;
    }
}
