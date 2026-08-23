package com.flexforge.common.registry;

import com.flexforge.common.PublicApi;

import java.util.List;

/**
 * DomainEventType ID 常量：与 docs/extension-points.md §2.3 active 集合一一对应，
 * R-GOV-03 门禁自动比对两侧集合，运行时事件发布器拒绝未登记 type。
 */
@PublicApi
public final class DomainEventTypes {

    /** 通用领域事件类型（DomainEvent 载荷见登记册）。 */
    public static final String DOMAIN = "event.domain";

    /** 登记册 active 全集，供注册-撤销测试与 R-GOV-03 比对遍历。 */
    public static final List<String> ALL = List.of(DOMAIN);

    private DomainEventTypes() {
    }
}
