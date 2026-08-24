package com.flexforge.system.infrastructure;

import java.time.Instant;
import java.util.List;

/**
 * 用户管理视图行（system 模块内部值对象）。
 */
public record UserAdminRecord(long id, String username, String displayName, String status,
                              List<String> roles, Instant createdAt) {

    public UserAdminRecord {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
