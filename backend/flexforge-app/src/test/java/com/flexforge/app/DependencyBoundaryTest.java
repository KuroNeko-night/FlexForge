package com.flexforge.app;

import com.flexforge.common.ExperimentalApi;
import com.flexforge.common.PublicApi;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 依赖边界骨架（docs/10 R1/R2，R-GOV-02）：P01 起持续运行，新模块加入时规则自动覆盖。
 *
 * <p>infrastructure 规则口径 = docs/10 §5.2：禁止跨模块引用他模块 infrastructure 包，
 * 同模块内部依赖自身 infrastructure 允许（回归见 {@link CrossModuleInfrastructureRuleTest}）。
 */
@AnalyzeClasses(packages = "com.flexforge", importOptions = ImportOption.DoNotIncludeTests.class)
class DependencyBoundaryTest {

    /** 从包名提取 flexforge 模块名（com.flexforge.common.x → common）；非模块包返回 null。 */
    static String moduleOf(JavaClass clazz) {
        String pkg = clazz.getPackageName();
        if (!pkg.startsWith("com.flexforge.")) {
            return null;
        }
        String rest = pkg.substring("com.flexforge.".length());
        int dot = rest.indexOf('.');
        String module = dot < 0 ? rest : rest.substring(0, dot);
        return module.isEmpty() ? null : module;
    }

    /** 判断类是否位于某模块的 infrastructure 包（com.flexforge.&lt;module&gt;.infrastructure..）。 */
    static boolean isInfrastructure(JavaClass clazz) {
        String pkg = clazz.getPackageName();
        return pkg.endsWith(".infrastructure") || pkg.contains(".infrastructure.");
    }

    @ArchTest
    static final ArchRule commonMustNotDependOnApp =
            noClasses().that().resideInAPackage("com.flexforge.common..")
                    .should().dependOnClassesThat().resideInAnyPackage("com.flexforge.app..");

    @ArchTest
    static final ArchRule commonMustNotDependOnFrameworkImplementation =
            noClasses().that().resideInAPackage("com.flexforge.common..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("org.springframework..", "jakarta..");

    /** docs/10 §5.2：只禁止跨模块 infrastructure 引用；同模块自身 infrastructure 允许（Issue #5）。 */
    @ArchTest
    static final ArchRule noCrossModuleInfrastructureReferences =
            noClasses().that().resideInAPackage("com.flexforge..")
                    .should(new ArchCondition<>("依赖其他模块的 infrastructure 包") {
                        @Override
                        public void check(JavaClass clazz, ConditionEvents events) {
                            String originModule = moduleOf(clazz);
                            if (originModule == null) {
                                return;
                            }
                            clazz.getDirectDependenciesFromSelf().forEach(dependency -> {
                                JavaClass target = dependency.getTargetClass();
                                String targetModule = moduleOf(target);
                                boolean crossModule = targetModule != null && !targetModule.equals(originModule);
                                if (crossModule && isInfrastructure(target)) {
                                    // noClasses 规则：条件 satisfied 即为违规
                                    events.add(SimpleConditionEvent.satisfied(clazz, String.format(
                                            "%s -> %s（%s 模块的 infrastructure，docs/10 §5.2 禁止跨模块引用）",
                                            dependency.getOriginClass().getName(), target.getName(), targetModule)));
                                }
                            });
                        }
                    });

    @ArchTest
    static final ArchRule modulesAreFreeOfCycles =
            SlicesRuleDefinition.slices().matching("com.flexforge.(*)..").should().beFreeOfCycles();

    /**
     * docs/10 §5.4：跨模块只能引用对方标注 @PublicApi（或 @ExperimentalApi）的类型；
     * 未标注类型被跨模块引用即失败（回归见 {@link PublicApiBoundaryRuleTest}）。
     */
    @ArchTest
    static final ArchRule crossModuleReferencesRequirePublicApi =
            noClasses().that().resideInAPackage("com.flexforge..")
                    .should(new ArchCondition<>("引用其他模块未标注 @PublicApi/@ExperimentalApi 的类型") {
                        @Override
                        public void check(JavaClass clazz, ConditionEvents events) {
                            String originModule = moduleOf(clazz);
                            if (originModule == null) {
                                return;
                            }
                            clazz.getDirectDependenciesFromSelf().forEach(dependency -> {
                                JavaClass target = dependency.getTargetClass();
                                String targetModule = moduleOf(target);
                                boolean crossModule = targetModule != null && !targetModule.equals(originModule);
                                boolean exposed = target.isAnnotatedWith(PublicApi.class)
                                        || target.isAnnotatedWith(ExperimentalApi.class);
                                if (crossModule && !exposed) {
                                    // noClasses 规则：条件 satisfied 即为违规
                                    events.add(SimpleConditionEvent.satisfied(clazz, String.format(
                                            "%s -> %s（%s 模块的未公开类型，docs/10 §5.4 禁止跨模块引用）",
                                            dependency.getOriginClass().getName(), target.getName(), targetModule)));
                                }
                            });
                        }
                    });
}
