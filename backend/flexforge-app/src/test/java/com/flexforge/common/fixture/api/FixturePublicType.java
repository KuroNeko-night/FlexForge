package com.flexforge.common.fixture.api;

import com.flexforge.common.PublicApi;

/**
 * 回归 fixture：common 模块的 @PublicApi 类型，跨模块引用必须放行。
 */
@PublicApi
public class FixturePublicType {

    public String value() {
        return "public";
    }
}
