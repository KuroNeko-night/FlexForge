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
            // 开发者职责入口（docs/02 §1：创建实体/字段/视图配置；路由 P04 落地）——
            // 使三类角色菜单互异（docs/09 P03 验收 1，复审 P1-2）
            new NavigationContribution("data-model", "数据模型", "/meta/entities", "database",
                    500, "DEVELOPER"),
            // 文件工具页（P23，FR-PLUGIN-14）：平台能力页（空态=无 ACTIVE 文件处理器，
            // NFR-SKEL-01 不破）。菜单=登录可见（单键 permissionKey 表达不了 ADMIN|USER
            // 组合）；执行权限由服务端 @RequireRole({ADMIN,USER}) 收口（S2，菜单只是体验）
            new NavigationContribution("file-tools", "文件工具", "/tools", "sliders",
                    400, null),
            new NavigationContribution("system-management", "系统管理", "/system/users", "settings",
                    900, "ADMIN"),
            // 审计日志（P24，FR-AUTH-04）：消费 P03 审计查询 API 的管理页，仅管理员
            new NavigationContribution("system-audit", "审计日志", "/system/audit", "database",
                    910, "ADMIN"));

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
