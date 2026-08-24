package com.flexforge.data.domain;

import java.util.UUID;

/**
 * 动态记录 ID 工厂（QG-4 单一生成点）：统一 "rec-"+UUID，对外不透明（V005 列宽内）。
 */
public final class RecordIds {

    private RecordIds() {
    }

    /** 新记录 ID。 */
    public static String newId() {
        return "rec-" + UUID.randomUUID();
    }
}
