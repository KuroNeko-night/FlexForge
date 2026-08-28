package com.flexforge.plugin.domain;

import com.flexforge.common.PublicApi;

/** 激活状态机（docs/07 §3-§4、docs/09 P08）：同插件同刻仅一个 STARTING/ACTIVE。 */
@PublicApi
public enum ActivationStatus {
    STARTING("STARTING"),
    ACTIVE("ACTIVE"),
    STOPPING("STOPPING"),
    STOPPED("STOPPED"),
    FAILED("FAILED");

    private final String wireName;

    ActivationStatus(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static ActivationStatus fromName(String name) {
        for (ActivationStatus status : values()) {
            if (status.wireName.equals(name)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知激活状态: " + name);
    }

    /** 是否占用"同插件唯一激活"槽位（docs/07 §3）。 */
    public boolean occupiesSlot() {
        return this == STARTING || this == ACTIVE;
    }
}
