package com.flexforge.app;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #5 回归：跨模块 infrastructure 规则的放行/拦截双向断言。
 *
 * <p>fixture 类不参与 @AnalyzeClasses 主扫描（DoNotIncludeTests），在此显式导入验证规则语义。
 */
class CrossModuleInfrastructureRuleTest {

    @Test
    void sameModuleInfrastructureDependencyIsAllowed() {
        JavaClasses sameModule = new ClassFileImporter().importPackages("com.flexforge.common.fixture");

        EvaluationResult result = DependencyBoundaryTest.noCrossModuleInfrastructureReferences
                .evaluate(sameModule);

        assertThat(result.getFailureReport().getDetails())
                .as("同模块依赖自身 infrastructure 必须放行（docs/10 §5.2）")
                .isEmpty();
    }

    @Test
    void crossModuleInfrastructureDependencyIsRejected() {
        JavaClasses crossModule = new ClassFileImporter().importPackages("com.flexforge.app.fixture");

        EvaluationResult result = DependencyBoundaryTest.noCrossModuleInfrastructureReferences
                .evaluate(crossModule);

        assertThat(result.getFailureReport().getDetails())
                .as("跨模块依赖他模块 infrastructure 必须报违规（粒度为依赖边）")
                .isNotEmpty()
                .allSatisfy(detail -> assertThat(detail).contains("FixtureCrossModuleInfrastructureUser"));
    }
}
