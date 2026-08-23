package com.flexforge.app;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 依赖边界骨架（docs/10 R1/R2，R-GOV-02）：P01 起持续运行，新模块加入时规则自动覆盖。
 */
@AnalyzeClasses(packages = "com.flexforge", importOptions = ImportOption.DoNotIncludeTests.class)
class DependencyBoundaryTest {

    @ArchTest
    static final ArchRule commonMustNotDependOnApp =
            noClasses().that().resideInAPackage("com.flexforge.common..")
                    .should().dependOnClassesThat().resideInAnyPackage("com.flexforge.app..");

    @ArchTest
    static final ArchRule commonMustNotDependOnFrameworkImplementation =
            noClasses().that().resideInAPackage("com.flexforge.common..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("org.springframework..", "jakarta..");

    @ArchTest
    static final ArchRule noInfrastructureCrossModuleReferences =
            noClasses().that().resideInAPackage("com.flexforge..")
                    .should().dependOnClassesThat().resideInAnyPackage("..infrastructure..");

    @ArchTest
    static final ArchRule modulesAreFreeOfCycles =
            SlicesRuleDefinition.slices().matching("com.flexforge.(*)..").should().beFreeOfCycles();
}
