/**
 * 运行时契约类型：ServiceKey、Registration、DomainEvent。
 *
 * <p>提供：插件/模块注册表交互所需的稳定接口与值对象（docs/08 §2）。
 * <p>不提供：注册表实现（在 flexforge-runtime）、Spring 装配、持久化。
 * <p>变更流程：additive 直接变更；breaking 需 ADR 并同一 PR 迁移全部消费方（docs/10 §5）。
 */
package com.flexforge.common.contract;
