package com.flexforge.common.fixture.infrastructure;

/**
 * 回归 fixture（Issue #5）：模拟 common 模块自身的 infrastructure 适配器。
 *
 * <p>仅供 CrossModuleInfrastructureRuleTest 显式导入，不参与主代码扫描。
 */
public class FixtureCommonInfrastructure {

    public String describe() {
        return "common-fixture-infrastructure";
    }
}
