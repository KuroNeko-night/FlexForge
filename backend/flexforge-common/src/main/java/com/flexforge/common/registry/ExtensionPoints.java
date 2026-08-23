package com.flexforge.common.registry;

import com.flexforge.common.PublicApi;

import java.util.List;

/**
 * ExtensionPoint ID 常量：与 docs/extension-points.md §2.2 active 集合一一对应，
 * R-GOV-03 门禁自动比对两侧集合；运行时注册表拒绝未登记 ID。
 */
@PublicApi
public final class ExtensionPoints {

    /** 导航/菜单贡献（P03 准备，P08 插件注册）。 */
    public static final String NAVIGATION = "extension.navigation";

    /** 字段渲染器映射（P04 准备，P06 落地）。 */
    public static final String FIELD_RENDERER = "extension.field-renderer";

    /** 记录动作（P05 准备，P09 示例落地）。 */
    public static final String RECORD_ACTION = "extension.record-action";

    /** 登记册 active 全集，供注册-撤销测试与 R-GOV-03 比对遍历。 */
    public static final List<String> ALL = List.of(NAVIGATION, FIELD_RENDERER, RECORD_ACTION);

    private ExtensionPoints() {
    }
}
