package com.flexforge.common.contract;

import com.flexforge.common.PublicApi;

import java.util.Objects;

/**
 * 类型安全的服务键（docs/extension-points.md §2.1）：id 必须使用登记册中的 ServiceKey ID，
 * serviceType 用于注册表取值时的类型收敛，避免裸字符串查服务。
 *
 * @param <T> 该服务键对应的服务接口类型
 */
@PublicApi
public record ServiceKey<T>(String id, Class<T> serviceType) {

    public ServiceKey {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(serviceType, "serviceType");
    }

    public static <T> ServiceKey<T> of(String id, Class<T> serviceType) {
        return new ServiceKey<>(id, serviceType);
    }
}
