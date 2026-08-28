package com.flexforge.plugin.application;

import com.flexforge.plugin.domain.DependencySpec;
import com.flexforge.plugin.domain.PluginValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 依赖解析（docs/09 P07：缺失能指出具体插件和版本范围）。 */
class DependencyResolverTest {

    private final DependencyResolver resolver = new DependencyResolver();

    @Test
    void rangeSemantics() {
        assertThat(DependencyResolver.satisfies("1.2.3", "*")).isTrue();
        assertThat(DependencyResolver.satisfies("1.2.3", "1.2.3")).isTrue();
        assertThat(DependencyResolver.satisfies("1.2.4", "1.2.3")).isFalse();
        assertThat(DependencyResolver.satisfies("1.9.0", "^1.2.0")).isTrue();
        assertThat(DependencyResolver.satisfies("2.0.0", "^1.2.0")).isFalse();
        assertThat(DependencyResolver.satisfies("1.1.9", "^1.2.0")).isFalse();
        assertThat(DependencyResolver.satisfies("0.2.5", "^0.2.0")).isTrue();
        assertThat(DependencyResolver.satisfies("0.3.0", "^0.2.0")).isFalse();
    }

    @Test
    void resolvesAgainstImportedVersions() {
        resolver.resolve(
                List.of(new DependencySpec("vendor.thing", "^1.0.0")),
                id -> List.of("0.9.0", "1.4.2", "2.0.0"));
    }

    @Test
    void missingDependencyNamesPluginAndRange() {
        assertThatThrownBy(() -> resolver.resolve(
                        List.of(new DependencySpec("vendor.thing", "^1.2.0")),
                        id -> List.of("1.1.0")))
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("vendor.thing")
                .hasMessageContaining("^1.2.0")
                .extracting("code").isEqualTo("dependency_missing");
    }

    @Test
    void noDependenciesAlwaysResolves() {
        resolver.resolve(List.of(), id -> {
            throw new AssertionError("不应查询");
        });
        assertThat(Map.of()).isEmpty();
    }
}
