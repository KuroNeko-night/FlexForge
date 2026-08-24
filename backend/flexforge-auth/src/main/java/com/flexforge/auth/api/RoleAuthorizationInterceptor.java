package com.flexforge.auth.api;

import com.flexforge.auth.AuthPrincipal;
import com.flexforge.auth.PermissionDeniedException;
import com.flexforge.common.PublicApi;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

/**
 * {@link RequireRole} 统一执行器（docs/13 §3.2.1）：按注解声明校验认证主体角色，
 * 未标注的方法不做角色限制（仍受 JwtAuthFilter 认证保护）。
 */
@PublicApi
public class RoleAuthorizationInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequireRole requireRole = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (requireRole == null) {
            return true;
        }
        AuthPrincipal principal = (AuthPrincipal) request.getAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE);
        if (principal == null) {
            // 理论不可达（JwtAuthFilter 先行）；防御性拒绝而非放行（fail-closed）
            throw new PermissionDeniedException();
        }
        boolean allowed = Arrays.stream(requireRole.value()).anyMatch(principal::hasRole);
        if (!allowed) {
            throw new PermissionDeniedException();
        }
        return true;
    }
}
