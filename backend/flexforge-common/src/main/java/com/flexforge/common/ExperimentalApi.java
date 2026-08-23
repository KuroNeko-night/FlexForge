package com.flexforge.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

/**
 * 实验性跨模块契约标注（docs/09 P02）：允许跨模块引用，但不承诺 MVP 内稳定，
 * 可能随实现阶段调整；稳定后升级为 {@link PublicApi}。
 */
@Documented
@Target(ElementType.TYPE)
@PublicApi
public @interface ExperimentalApi {
}
