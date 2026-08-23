package com.flexforge.common.fixture;

import com.flexforge.common.fixture.infrastructure.FixtureCommonInfrastructure;

/**
 * 回归 fixture（Issue #5）：同模块类依赖自身 infrastructure，docs/10 §5.2 允许。
 */
public class FixtureSameModuleInfrastructureUser {

    public String use() {
        return new FixtureCommonInfrastructure().describe();
    }
}
