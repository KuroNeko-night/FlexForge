package com.flexforge.ai.model;

import com.flexforge.ai.config.AiConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 模型端口运行时路由（P15 设置页）：按有效配置（DB &gt; env &gt; fixture，
 * docs/13 §3.6-5）每次调用时选择 fixture 或 http 实现——配置变更即时生效，
 * 无需重启。本类是唯一的 ModelPort Bean（两个实现不注册为 Bean，由本类持有），
 * 测试桩以 @Primary ModelPort 注入即可整体替换行为。
 */
@Component
public class RoutingModelPort implements ModelPort {

    private final FixtureModelPort fixture;
    private final HttpModelPort http;
    private final AiConfigService configs;

    // 双构造器（另有测试直连包私有构造器），Spring 装配须显式指定本构造器
    @Autowired
    public RoutingModelPort(AiConfigService configs) {
        this.fixture = new FixtureModelPort();
        this.http = new HttpModelPort(configs);
        this.configs = configs;
    }

    /** 测试直连装配（固定委托，不读运行时配置）。 */
    RoutingModelPort(FixtureModelPort fixture, HttpModelPort http, AiConfigService configs) {
        this.fixture = fixture;
        this.http = http;
        this.configs = configs;
    }

    @Override
    public ModelReply complete(ModelRequest request) {
        return route().complete(request);
    }

    @Override
    public String name() {
        return route().name();
    }

    private ModelPort route() {
        return "http".equals(configs.effective().provider()) ? http : fixture;
    }
}
