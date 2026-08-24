package com.flexforge.system.application;

import com.flexforge.auth.AuthPrincipal;
import com.flexforge.common.contract.NavigationContribution;
import com.flexforge.runtime.InMemoryExtensionRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 菜单聚合回归（docs/09 P03 三角色差异 + extension.navigation 真实消费方）：
 * 内置菜单按角色过滤、运行时贡献合并、注册撤销即时生效。
 */
class MenuServiceTest {

    private final InMemoryExtensionRegistry registry = new InMemoryExtensionRegistry();
    private final MenuService menuService = new MenuService(registry);

    private static List<String> keys(List<NavigationContribution> menus) {
        return menus.stream().map(NavigationContribution::key).toList();
    }

    @Test
    void threeRolesSeeDifferentMenus() {
        // 扁平角色（无继承）：管理员=工作台+系统管理；开发者=工作台+数据模型；普通用户=仅工作台（验收 1）
        assertThat(keys(menuService.menusFor(new AuthPrincipal(1L, List.of("ADMIN")))))
                .containsExactly("workbench", "system-management");
        assertThat(keys(menuService.menusFor(new AuthPrincipal(2L, List.of("DEVELOPER")))))
                .containsExactly("workbench", "data-model");
        assertThat(keys(menuService.menusFor(new AuthPrincipal(3L, List.of("USER")))))
                .containsExactly("workbench");
    }

    @Test
    void normalUserAndDeveloperDoNotSeeSystemManagement() {
        assertThat(keys(menuService.menusFor(new AuthPrincipal(2L, List.of("USER"))))
                .contains("workbench")).isTrue();
        assertThat(keys(menuService.menusFor(new AuthPrincipal(2L, List.of("USER"))))
                .contains("system-management")).isFalse();
        assertThat(keys(menuService.menusFor(new AuthPrincipal(3L, List.of("DEVELOPER"))))
                .contains("system-management")).isFalse();
    }

    @Test
    void contributedNavigationAppearsAndRevokesImmediately() {
        var contribution = new NavigationContribution("plugin-orders", "订单", "/orders", "list",
                50, "USER");
        var registration = registry.register("extension.navigation", contribution, "act-test-001");

        assertThat(keys(menuService.menusFor(new AuthPrincipal(9L, List.of("USER")))))
                .containsExactly("plugin-orders", "workbench");
        // 未持有所需角色的用户看不到贡献
        assertThat(keys(menuService.menusFor(new AuthPrincipal(9L, List.of("DEVELOPER")))))
                .doesNotContain("plugin-orders");

        registration.close();
        assertThat(keys(menuService.menusFor(new AuthPrincipal(9L, List.of("USER")))))
                .doesNotContain("plugin-orders");
    }
}
