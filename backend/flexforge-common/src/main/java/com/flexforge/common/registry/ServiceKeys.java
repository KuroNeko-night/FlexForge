package com.flexforge.common.registry;

import com.flexforge.common.PublicApi;

import java.util.List;

/**
 * ServiceKey ID 常量：与 docs/extension-points.md §2.1 active 集合一一对应，
 * R-GOV-03 门禁自动比对两侧集合，运行时 ServiceRegistry 拒绝未登记 ID。
 */
@PublicApi
public final class ServiceKeys {

    /** MetaRegistry：实体/字段/视图查询与缓存失效（P04 落地）。 */
    public static final String META = "service.meta";

    /** 受控动态数据读写：白名单字段映射、参数化查询、排序分页（P05 落地）。 */
    public static final String DATA_ACCESS = "service.data-access";

    /** 审计事件写入端口（P03 落地）。 */
    public static final String AUDIT = "service.audit";

    /** 登记册 active 全集，供注册-撤销测试与 R-GOV-03 比对遍历。 */
    public static final List<String> ALL = List.of(META, DATA_ACCESS, AUDIT);

    private ServiceKeys() {
    }
}
