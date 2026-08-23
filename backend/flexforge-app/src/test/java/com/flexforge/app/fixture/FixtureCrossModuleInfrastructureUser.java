package com.flexforge.app.fixture;

import com.flexforge.common.fixture.infrastructure.FixtureCommonInfrastructure;

/**
 * 回归 fixture（Issue #5）：app 模块依赖 common 的 infrastructure，docs/10 §5.2 禁止。
 */
public class FixtureCrossModuleInfrastructureUser {

    public String use() {
        return new FixtureCommonInfrastructure().describe();
    }
}
