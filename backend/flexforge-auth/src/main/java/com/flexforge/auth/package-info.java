/**
 * flexforge-auth：登录、JWT、RBAC 与当前用户。
 *
 * <p>提供：BCrypt 密码哈希（S3）、JWT HS256 签发/校验（S4 密钥仅 env）、登录防暴破
 * （5 次失败锁 10 分钟）、登录/退出/当前用户接口与 JWT 认证过滤器（docs/13 §3.1）。
 * <p>不提供：用户/角色管理接口、审计查询（flexforge-system，P03 迭代 2）、对象级授权（各业务模块）。
 * <p>变更流程：安全相关变更必须对齐 docs/13 S1-S9 并补失败路径测试；公开类型 @PublicApi。
 */
package com.flexforge.auth;
