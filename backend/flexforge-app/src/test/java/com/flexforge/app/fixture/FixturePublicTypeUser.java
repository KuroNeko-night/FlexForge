package com.flexforge.app.fixture;

import com.flexforge.common.fixture.api.FixturePublicType;

/**
 * 回归 fixture：app 依赖 common 的 @PublicApi 类型，规则必须放行。
 */
public class FixturePublicTypeUser {

    public String use() {
        return new FixturePublicType().value();
    }
}
