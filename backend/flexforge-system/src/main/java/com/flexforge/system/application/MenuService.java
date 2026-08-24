package com.flexforge.system.application;

import com.flexforge.auth.AuthPrincipal;
import com.flexforge.common.PublicApi;
import com.flexforge.common.contract.NavigationContribution;
import com.flexforge.common.registry.ExtensionPoints;
import com.flexforge.runtime.InMemoryExtensionRegistry;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 菜单聚合（docs/09 P03：三类角色在菜单上表现不同）：平台内置骨架菜单（工作台全员 +
 * 系统管理仅管理员，ADR-0004：业务菜单只经插件注册）合并 extension.navigation 运行时
 * 贡献（登记册 §2.2 的 MVP 消费方），按认证主体角色过滤 permissionKey 后按 order 升序。
 */
@PublicApi
@Service
public class MenuService {

    private static final List<NavigationContribution> BUILTIN_MENUS = List.of(
            new NavigationContribution("workbench", "工作台", "/workbench", "dashboard", 100, null),
            new NavigationContribution("system-management", "系统管理", "/system/users", "settings",
                    900, "ADMIN"));

    private final InMemoryExtensionRegistry extensionRegistry;

    public MenuService(InMemoryExtensionRegistry extensionRegistry) {
        this.extensionRegistry = extensionRegistry;
    }

    public List<NavigationContribution> menusFor(AuthPrincipal principal) {
        Objects.requireNonNull(principal, "principal");
        List<NavigationContribution> contributed = extensionRegistry.contributions(
                ExtensionPoints.NAVIGATION, NavigationContribution.class);
        return java.util.stream.Stream.concat(BUILTIN_MENUS.stream(), contributed.stream())
                .filter(menu -> menu.permissionKey() == null || principal.hasRole(menu.permissionKey()))
                .sorted(Comparator.comparingInt(NavigationContribution::order)
                        .thenComparing(NavigationContribution::key))
                .toList();
    }
}
