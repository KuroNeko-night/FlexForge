package com.flexforge.common.fixture.api;

/**
 * 回归 fixture：common 模块的未标注类型，被跨模块引用必须报违规（docs/10 §5.4）。
 */
public class FixtureInternalType {

    public String value() {
        return "internal";
    }
}
