package com.flexforge.plugin.domain;

import com.flexforge.common.PublicApi;

import java.util.Objects;

/**
 * 依赖声明（plugin.json dependencies[] 项）：目标插件稳定 ID + 版本范围。
 * MVP 范围语法：精确 `1.2.0`、前缀 `^1.2.0`（&gt;=1.2.0 &lt;2.0.0）、通配 `*`。
 */
@PublicApi
public record DependencySpec(String pluginId, String versionRange) {

    public DependencySpec {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(versionRange, "versionRange");
    }
}
