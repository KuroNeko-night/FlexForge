package com.flexforge.plugin.domain;

import com.flexforge.common.PublicApi;

import java.util.List;
import java.util.Map;

/** 导入/校验结果预览（docs/03 §8 InstallPreview）：幂等命中时 isNew=false。 */
@PublicApi
public record InstallPreview(
        boolean isNew,
        String pluginId,
        String name,
        String version,
        int capabilityLevel,
        String contentHash,
        String versionId,
        Map<String, String> scriptChecksums,
        List<DependencySpec> dependencies) {
}
