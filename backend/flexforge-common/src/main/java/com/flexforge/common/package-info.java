/**
 * flexforge-common：平台级共享常量与基础契约。
 *
 * <p>提供：跨模块稳定常量（ApiConstants）、@PublicApi/@ExperimentalApi 标注、requestId 生成
 * （RequestIds）；子包 contract（运行时契约类型）、api（错误模型与分页）、audit（审计端口）、
 * registry（登记册 ID 常量）（docs/10 §5：作为基础设施，不依赖业务模块）。
 * <p>不提供：业务逻辑、领域模型、对 Spring 或数据库实现的依赖、注册表实现（flexforge-runtime）。
 * <p>变更流程：新增契约/常量为 additive；改名、删除、改值为 breaking，需 ADR 并同步所有消费方。
 */
package com.flexforge.common;
