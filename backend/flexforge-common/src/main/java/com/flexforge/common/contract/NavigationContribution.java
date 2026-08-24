package com.flexforge.common.contract;

import com.flexforge.common.PublicApi;

/**
 * extension.navigation 贡献载荷（docs/extension-points.md §2.2）：插件/平台向菜单注册的单项。
 * permissionKey 为 Roles 常量（平台菜单用），为 null 表示全员可见；order 升序。
 */
@PublicApi
public record NavigationContribution(
        String key,
        String title,
        String route,
        String icon,
        int order,
        String permissionKey
) {

    public NavigationContribution {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("navigation key 必填");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("navigation title 必填");
        }
        if (route == null || route.isBlank()) {
            throw new IllegalArgumentException("navigation route 必填");
        }
    }
}
