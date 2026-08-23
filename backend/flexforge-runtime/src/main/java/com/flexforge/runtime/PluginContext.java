package com.flexforge.runtime;

import com.flexforge.common.ExperimentalApi;
import com.flexforge.common.contract.DomainEvent;
import com.flexforge.common.contract.ServiceKey;

/**
 * 插件运行时上下文（docs/08 §3，P08 完整冻结）：插件代码通过本上下文获取平台服务
 * 与发布事件，不直接触碰平台内部实现。
 */
@ExperimentalApi
public interface PluginContext {

    /** 当前激活身份；该身份下的一切注册项在停用/卸载时整体撤销。 */
    String activationId();

    /** 按登记册 ServiceKey 获取平台服务；未注册抛 NoSuchElementException。 */
    <T> T service(ServiceKey<T> key);

    /** 以当前激活身份发布领域事件。 */
    void publish(DomainEvent event);
}
