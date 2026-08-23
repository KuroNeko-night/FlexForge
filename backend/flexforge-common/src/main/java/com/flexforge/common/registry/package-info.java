/**
 * 扩展点登记册 ID 常量：ServiceKeys / ExtensionPoints / DomainEventTypes。
 *
 * <p>提供：登记册（docs/extension-points.md）active 集合的代码侧镜像，R-GOV-03 强制一致。
 * <p>不提供：运行时注册表（flexforge-runtime）、登记流程本身（走文档登记）。
 * <p>变更流程：新增 ID 先在登记册登记（additive），再同步本包常量与 ALL 集合。
 */
package com.flexforge.common.registry;
