package com.flexforge.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

/**
 * 跨模块公开契约标注（docs/10 §5.4）：只有本标注（或 {@link ExperimentalApi}）的类型
 * 允许被其他 flexforge 模块引用，ArchUnit 依赖边界规则强制执行。
 *
 * <p>被标注类型的契约按扩展点登记册口径演化：additive 直接变更，breaking 需 ADR。
 */
@Documented
@Target(ElementType.TYPE)
@PublicApi
public @interface PublicApi {
}
