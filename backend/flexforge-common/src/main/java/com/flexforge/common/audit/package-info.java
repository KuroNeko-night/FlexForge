/**
 * 审计契约：AuditEvent 值对象与 AuditEventPort 写入端口。
 *
 * <p>提供：跨模块统一的审计事件契约（service.audit）。
 * <p>不提供：存储实现（flexforge-system P03 落地）、查询接口（P03）。
 * <p>变更流程：additive 直接变更；breaking 需 ADR 并同一 PR 迁移全部消费方。
 */
package com.flexforge.common.audit;
