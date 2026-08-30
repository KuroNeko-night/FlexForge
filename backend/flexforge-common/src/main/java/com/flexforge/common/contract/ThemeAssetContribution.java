package com.flexforge.common.contract;

import com.flexforge.common.PublicApi;

import java.util.Set;

/**
 * extension.theme-asset 贡献载荷（docs/extension-points.md §2.2，FR-PLUGIN-11）：
 * 插件携带的美术资产声明。kind ∈ background/icon/animation/tokens/locale（P12.5：tokens
 * 指向 assets/ 内 JSON 键值表，覆盖 --ff-* 设计令牌实现插件换肤）；path 指向包内
 * 经 S6 校验的 assets/ 静态资源（serve 端点按 activationId 取回）；scope 缺省全局。
 */
@PublicApi
public record ThemeAssetContribution(
        String key,
        String kind,
        String path,
        String scope) {

    public static final Set<String> KINDS = Set.of("background", "icon", "animation", "tokens", "locale");

    public ThemeAssetContribution {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("themeAsset key 必填");
        }
        if (kind == null || !KINDS.contains(kind)) {
            throw new IllegalArgumentException("themeAsset kind 非法: " + kind);
        }
        // 契约层仅做前缀防线：穿越/段合法性由 flexforge-plugin 导入链强校验（S6，docs/13 §3.5），
        // serve 端点按存储键精确匹配兜底；此处不重复整套路径算法，避免两处规则漂移
        if (path == null || path.isBlank() || !path.startsWith("assets/")) {
            throw new IllegalArgumentException("themeAsset path 必须是包内 assets/ 相对路径");
        }
        scope = scope == null || scope.isBlank() ? null : scope;
    }
}
