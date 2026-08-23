package com.flexforge.app.fixture;

import com.flexforge.common.fixture.api.FixtureInternalType;

/**
 * 回归 fixture：app 依赖 common 的未标注类型，规则必须报违规。
 */
public class FixtureInternalTypeUser {

    public String use() {
        return new FixtureInternalType().value();
    }
}
