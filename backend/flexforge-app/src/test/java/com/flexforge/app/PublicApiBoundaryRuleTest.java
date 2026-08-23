package com.flexforge.app;

import com.flexforge.app.fixture.FixtureInternalTypeUser;
import com.flexforge.app.fixture.FixturePublicTypeUser;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/10 §5.4 跨模块公开性规则的双向回归：@PublicApi 放行、未标注拦截（防规则失效）。
 *
 * <p>按类导入隔离被测 fixture，避免同包其他 fixture 的依赖边混入断言。
 */
class PublicApiBoundaryRuleTest {

    @Test
    void publicApiCrossModuleReferenceIsAllowed() {
        JavaClasses classes = new ClassFileImporter().importClasses(FixturePublicTypeUser.class);

        EvaluationResult result = DependencyBoundaryTest.crossModuleReferencesRequirePublicApi.evaluate(classes);

        assertThat(result.getFailureReport().getDetails())
                .as("引用 @PublicApi 类型必须放行")
                .isEmpty();
    }

    @Test
    void nonPublicCrossModuleReferenceIsRejected() {
        JavaClasses classes = new ClassFileImporter().importClasses(FixtureInternalTypeUser.class);

        EvaluationResult result = DependencyBoundaryTest.crossModuleReferencesRequirePublicApi.evaluate(classes);

        assertThat(result.getFailureReport().getDetails())
                .as("引用未标注类型必须报违规（粒度为依赖边）")
                .isNotEmpty()
                .allSatisfy(detail -> assertThat(detail).contains("FixtureInternalTypeUser"));
    }
}
