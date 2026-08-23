/**
 * API 契约：统一错误模型与分页请求/响应。
 *
 * <p>提供：ErrorResponse/ErrorCodes（稳定错误码）、PageQuery/PageResult（分页白名单与上限）。
 * <p>不提供：REST 装配与异常翻译（flexforge-app，P02 迭代 2）、业务校验。
 * <p>变更流程：新增错误码/字段为 additive；改名删除需 ADR（docs/extension-points.md §2.4）。
 */
package com.flexforge.common.api;
