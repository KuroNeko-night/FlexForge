package com.flexforge.auth;

import com.flexforge.common.PublicApi;

import java.util.List;

/**
 * JWT 载荷还原的认证主体（docs/13 §3.1.4：payload 只放用户 ID、角色与时间）。
 * 用户名/显示名等资料由 /me 等接口按需从存储加载，不进令牌。
 */
@PublicApi
public record AuthPrincipal(long userId, List<String> roles) {

    public AuthPrincipal {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
