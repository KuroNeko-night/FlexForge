package com.flexforge.meta.domain;

import com.flexforge.common.PublicApi;

/**
 * 视图类型（FR-META-03；P17 扩 kanban）：list 配置列表列与查询字段；form 配置
 * 表单顺序；kanban 按 enum 字段分列的看板（groupBy 必填，ViewRules 校验）。
 * 一个实体每类视图至多一份（V004 唯一约束）；viewType 创建后不可改（P04 口径）。
 */
@PublicApi
public enum ViewType {

    LIST("list"),
    FORM("form"),
    KANBAN("kanban");

    private final String wireName;

    ViewType(String wireName) {
        this.wireName = wireName;
    }

    /** 对外/API 名（存储与 JSON 一致）。 */
    public String wireName() {
        return wireName;
    }

    /** 从存储/API 名解析；未知值拒绝。 */
    public static ViewType fromName(String name) {
        for (ViewType type : values()) {
            if (type.wireName.equals(name)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知视图类型: " + name + "（允许: list/form/kanban）");
    }
}
