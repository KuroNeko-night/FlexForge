package com.flexforge.auth.infrastructure;

import java.util.List;

/**
 * sys_user 连同角色的存储行（模块内部值对象）。
 */
public record UserRecord(long id, String username, String passwordHash, String displayName,
                         String status, List<String> roles) {

    public UserRecord {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    public boolean active() {
        return "ACTIVE".equals(status);
    }
}
