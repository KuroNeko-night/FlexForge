package com.flexforge.auth.api;

import com.flexforge.common.PublicApi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 服务端角色授权声明（docs/13 §3.2.1：RBAC 校验集中在 api 层，禁止散落业务方法手写）。
 * 由 {@link RoleAuthorizationInterceptor} 统一执行：认证主体缺少任一所需角色时
 * 抛 PermissionDeniedException → 403 permission_denied（S2：前端隐藏只是体验）。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PublicApi
public @interface RequireRole {

    /** 允许访问的角色（Roles 常量，命中任意一个即通过）。 */
    String[] value();
}
