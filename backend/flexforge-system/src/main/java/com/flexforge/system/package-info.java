/**
 * flexforge-system：用户、角色、菜单与审计。
 *
 * <p>提供：service.audit 写入端口的 JDBC 落库（sys_audit_event，P03 迭代 1）；
 * 用户/角色/菜单管理与审计查询接口在 P03 迭代 2 落地。
 * <p>不提供：认证逻辑（flexforge-auth）、业务能力（ADR-0004，业务走插件）。
 * <p>变更流程：审计载荷与登记册 §2.1 service.audit 一致，additive 变更先登记；公开类型 @PublicApi。
 */
package com.flexforge.system;
