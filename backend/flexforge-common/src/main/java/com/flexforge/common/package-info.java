/**
 * flexforge-common：平台级共享常量与基础契约。
 *
 * <p>提供：API 前缀与分页上限等跨模块稳定常量（docs/10 §5：作为基础设施，不依赖业务模块）。
 * <p>不提供：业务逻辑、领域模型、对 Spring 或数据库实现的依赖。
 * <p>变更流程：新增常量为 additive；改名、删除、改值为 breaking，需 ADR 并同步所有消费方。
 */
package com.flexforge.common;
