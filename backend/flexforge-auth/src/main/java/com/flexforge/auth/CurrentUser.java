package com.flexforge.auth;

import com.flexforge.common.PublicApi;

import java.util.List;
import java.util.Objects;

/**
 * 已认证用户上下文：由 JWT 载荷还原，随请求属性传递；内部主键 long（docs/09 P02 契约）。
 */
@PublicApi
public record CurrentUser(long id, String username, String displayName, List<String> roles) {

    public CurrentUser {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(displayName, "displayName");
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
