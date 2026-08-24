package com.flexforge.meta.domain;

import java.util.UUID;

/**
 * 元数据对象 ID 工厂（QG-4 单一生成点）：统一 "meta-"+UUID，对外不透明（V004 列宽 64 内）。
 */
public final class MetaIds {

    private MetaIds() {
    }

    /** 新元数据对象 ID（实体/字段/视图共用 UUID 空间，靠上下文区分）。 */
    public static String newId() {
        return "meta-" + UUID.randomUUID();
    }
}
