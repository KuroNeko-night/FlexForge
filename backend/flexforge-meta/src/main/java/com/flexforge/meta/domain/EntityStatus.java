package com.flexforge.meta.domain;

import com.flexforge.common.PublicApi;

/**
 * 实体状态（FR-META-01）：draft 自由编辑；enabled 对外可见（breaking 变更被拒）；
 * disabled 停用（数据可能存在，breaking 变更同样被拒）。
 * 合法迁移：draft→enabled、enabled→disabled、disabled→enabled；同状态幂等放行。
 */
@PublicApi
public enum EntityStatus {

    DRAFT("draft"),
    ENABLED("enabled"),
    DISABLED("disabled");

    private final String wireName;

    EntityStatus(String wireName) {
        this.wireName = wireName;
    }

    /** 对外/API 名（存储与 JSON 一致）。 */
    public String wireName() {
        return wireName;
    }

    /** 从存储/API 名解析；未知值拒绝。 */
    public static EntityStatus fromName(String name) {
        for (EntityStatus status : values()) {
            if (status.wireName.equals(name)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知实体状态: " + name + "（允许: draft/enabled/disabled）");
    }

    /** 是否允许迁移到目标状态（同状态幂等放行）。 */
    public boolean canTransitionTo(EntityStatus target) {
        if (this == target) {
            return true;
        }
        return switch (this) {
            case DRAFT -> target == ENABLED;
            case ENABLED, DISABLED -> target == DISABLED || target == ENABLED;
        };
    }
}
