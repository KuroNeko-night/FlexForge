package com.flexforge.plugin.domain;

import com.flexforge.common.PublicApi;

import java.util.List;

/** 校验端点响应：通过时 findings 为空并附预览；失败时逐条列出（可诊断）。 */
@PublicApi
public record ValidationReport(boolean valid, InstallPreview preview, List<String> findings) {

    public ValidationReport {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public static ValidationReport ok(InstallPreview preview) {
        return new ValidationReport(true, preview, List.of());
    }

    public static ValidationReport failed(List<String> findings) {
        return new ValidationReport(false, null, findings);
    }
}
