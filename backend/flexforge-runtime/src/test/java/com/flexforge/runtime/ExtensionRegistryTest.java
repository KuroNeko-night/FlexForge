package com.flexforge.runtime;

import com.flexforge.common.contract.Registration;
import com.flexforge.common.registry.ExtensionPoints;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ExtensionRegistry 注册-撤销回归（P02 验收 + 未登记扩展点拒绝）。
 */
class ExtensionRegistryTest {

    /** navigation 贡献样例（record，equals 语义）用于验证重复等值贡献不误删。 */
    private record NavigationItem(String key, String title) {
    }

    private final InMemoryExtensionRegistry registry = new InMemoryExtensionRegistry();

    @Test
    void registeredContributionIsVisible() {
        NavigationItem item = new NavigationItem("nav-1", "菜单一");
        Registration registration = registry.register(ExtensionPoints.NAVIGATION, item, "act-001");

        assertThat(registration.isActive()).isTrue();
        assertThat(registry.contributions(ExtensionPoints.NAVIGATION)).containsExactly(item);
    }

    @Test
    void unknownExtensionPointIsRejected() {
        assertThatThrownBy(() -> registry.register("extension.not-registered", new Object(), "act-001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("extension.not-registered");
        assertThatThrownBy(() -> registry.contributions("extension.not-registered"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closedContributionIsInvisible() {
        NavigationItem item = new NavigationItem("nav-1", "菜单一");
        Registration registration = registry.register(ExtensionPoints.NAVIGATION, item, "act-001");
        registration.close();

        assertThat(registration.isActive()).isFalse();
        assertThat(registry.contributions(ExtensionPoints.NAVIGATION)).isEmpty();
    }

    @Test
    void doubleCloseIsIdempotent() {
        Registration registration = registry.register(ExtensionPoints.NAVIGATION, new Object(), "act-001");
        registration.close();

        assertThatCode(registration::close).doesNotThrowAnyException();
    }

    @Test
    void equalContributionsFromDifferentActivationsDoNotCrossDelete() {
        NavigationItem first = new NavigationItem("nav-1", "菜单一");
        NavigationItem second = new NavigationItem("nav-1", "菜单一");
        Registration registrationA = registry.register(ExtensionPoints.NAVIGATION, first, "act-001");
        registry.register(ExtensionPoints.NAVIGATION, second, "act-002");

        registrationA.close();

        assertThat(registry.contributions(ExtensionPoints.NAVIGATION)).containsExactly(second);
    }

    @Test
    void typedContributionsFilterByType() {
        registry.register(ExtensionPoints.NAVIGATION, new NavigationItem("nav-1", "菜单一"), "act-001");
        registry.register(ExtensionPoints.NAVIGATION, "raw-contribution", "act-001");

        List<NavigationItem> typed = registry.contributions(ExtensionPoints.NAVIGATION, NavigationItem.class);

        assertThat(typed).hasSize(1);
        assertThat(typed.get(0).title()).isEqualTo("菜单一");
    }

    @Test
    void closeAllRevokesOnlyThatActivation() {
        Registration mine = registry.register(ExtensionPoints.NAVIGATION, new Object(), "act-001");
        Object other = new Object();
        registry.register(ExtensionPoints.NAVIGATION, other, "act-002");

        var revoked = registry.closeAll("act-001");

        assertThat(revoked).hasSize(1);
        assertThat(mine.isActive()).isFalse();
        assertThat(registry.contributions(ExtensionPoints.NAVIGATION)).containsExactly(other);
    }

    @Test
    void everyRegisteredExtensionPointRoundTrips() {
        for (String pointId : ExtensionPoints.ALL) {
            Registration registration = registry.register(pointId, "demo", "act-loop");
            assertThat(registry.contributions(pointId)).containsExactly("demo");
            registration.close();
            assertThat(registry.contributions(pointId)).isEmpty();
        }
    }
}
