package com.flexforge.plugin.domain;

import com.flexforge.common.PublicApi;

import java.util.Objects;
import java.util.Set;

/**
 * theme-asset 贡献声明（plugin.json contributions.themeAssets[] 项，登记册 §2.2）：
 * kind ∈ background/icon/animation；path 指向包内 assets/ 白名单资源；scope 缺省全局。
 */
@PublicApi
public record ThemeAssetSpec(String key, String kind, String path, String scope) {

    public static final Set<String> KINDS = Set.of("background", "icon", "animation");

    public ThemeAssetSpec {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(path, "path");
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("themeAsset kind 非法: " + kind);
        }
        if (!path.startsWith("assets/")) {
            throw new IllegalArgumentException("themeAsset path 必须是包内 assets/ 相对路径: " + path);
        }
        scope = scope == null || scope.isBlank() ? null : scope;
    }
}
