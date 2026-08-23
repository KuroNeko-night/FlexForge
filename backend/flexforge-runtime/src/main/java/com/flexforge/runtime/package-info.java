/**
 * flexforge-runtime：PluginRuntime 契约与内存注册表。
 *
 * <p>提供：PluginContext、内存 ServiceRegistry / ExtensionRegistry / 领域事件发布器
 * （docs/08 §2 职责；注册项绑定 activationId，可整体撤销）。
 * <p>不提供：插件生命周期管理（flexforge-plugin，P07-P08）、持久化、Spring 装配（flexforge-app）。
 * <p>变更流程：跨模块公开类型必须 @PublicApi/@ExperimentalApi；新增扩展点先登记
 * docs/extension-points.md，再同步 common.registry 常量（R-GOV-03 强制一致）。
 */
package com.flexforge.runtime;
