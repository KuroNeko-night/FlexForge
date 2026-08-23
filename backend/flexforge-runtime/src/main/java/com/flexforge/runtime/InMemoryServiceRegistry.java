package com.flexforge.runtime;

import com.flexforge.common.PublicApi;
import com.flexforge.common.contract.Registration;
import com.flexforge.common.contract.ServiceKey;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存服务注册表（service.* 扩展点，docs/08 §3）：一个 ServiceKey 同时只允许一个活跃实例；
 * 重复注册失败、close 后可重新注册；closeAll 按激活身份整体撤销（NFR-PLUGIN-01）。
 */
@PublicApi
public final class InMemoryServiceRegistry {

    private final Map<String, Object> servicesById = new ConcurrentHashMap<>();
    private final Map<String, List<SimpleRegistration>> byActivation = new ConcurrentHashMap<>();

    /**
     * 注册服务实例；同 key 已有活跃实例时抛 IllegalStateException（失败路径）。
     */
    public synchronized <T> Registration register(ServiceKey<T> key, T instance, String activationId) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(activationId, "activationId");
        if (servicesById.containsKey(key.id())) {
            throw new IllegalStateException("service already registered: " + key.id());
        }
        servicesById.put(key.id(), instance);
        SimpleRegistration registration =
                new SimpleRegistration(activationId, () -> servicesById.remove(key.id()));
        byActivation.computeIfAbsent(activationId, k -> new CopyOnWriteArrayList<>()).add(registration);
        return registration;
    }

    /** 按键查找；未注册返回 empty。 */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> find(ServiceKey<T> key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable((T) servicesById.get(key.id()));
    }

    /** 按键查找；未注册抛 NoSuchElementException（含 key id，可诊断）。 */
    public <T> T require(ServiceKey<T> key) {
        return find(key).orElseThrow(() -> new NoSuchElementException("service not registered: " + key.id()));
    }

    /** 撤销某激活身份下的全部服务注册；幂等，返回被撤销的注册项快照。 */
    public synchronized List<Registration> closeAll(String activationId) {
        Objects.requireNonNull(activationId, "activationId");
        List<SimpleRegistration> registrations = byActivation.remove(activationId);
        if (registrations == null) {
            return List.of();
        }
        registrations.forEach(SimpleRegistration::close);
        return List.copyOf(registrations);
    }
}
