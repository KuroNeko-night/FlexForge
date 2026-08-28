package com.flexforge.plugin.domain;

import com.flexforge.common.PublicApi;

import java.time.Instant;
import java.util.Map;

/** plugin_version 行镜像（导入幂等命中等场景）。 */
@PublicApi
public record PluginVersionRecord(
        String id,
        String pluginId,
        String version,
        String contentHash,
        int capabilityLevel,
        String manifestJson,
        Map<String, String> scriptChecksums,
        long sizeBytes,
        Instant createdAt) {
}
