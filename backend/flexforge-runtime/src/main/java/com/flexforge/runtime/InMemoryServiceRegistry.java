package com.flexforge.runtime;

import com.flexforge.common.PublicApi;
import com.flexforge.common.contract.Registration;
import com.flexforge.common.contract.ServiceKey;
import com.flexforge.common.registry.ServiceKeys;

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
 *
 * <p>并发口径（审计 P2-1 + 复审 P3）：dispose 在注册表监视器内仅移除"当前映射对应的
 * 注册已非活跃"的条目——close 窗口期重注册（即使同一实例引用）的活跃映射绝不被旧
 * close 误删；register 对"已 close 但 dispose 未执行"的条目直接覆盖而非误报。
 */
@PublicApi
public final class InMemoryServiceRegistry {

    private record ActiveService(Object instance, SimpleRegistration registration) {
    }

    private final Map<String, ActiveService> servicesById = new ConcurrentHashMap<>();
    // 单独 close 的句柄仍保留在本表，直到 closeAll(activationId) 整体清理（close 幂等，无害），
    // 换取 closeAll 总能取到该身份的全量句柄快照
    private final Map<String, List<SimpleRegistration>> byActivation = new ConcurrentHashMap<>();

    /**
     * 注册服务实例；key.id 必须在 docs/extension-points.md 登记册 active 集合内，
     * 同 key 已有活跃实例时抛 IllegalStateException（均为失败路径）。
     */
    public synchronized <T> Registration register(ServiceKey<T> key, T instance, String activationId) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(activationId, "activationId");
        requireKnownServiceKey(key.id());
        ActiveService existing = servicesById.get(key.id());
        if (existing != null && existing.registration().isActive()) {
            throw new IllegalStateException("service already registered: " + key.id());
        }
        SimpleRegistration registration = new SimpleRegistration(activationId, () -> {
            synchronized (InMemoryServiceRegistry.this) {
                // 仅移除非活跃映射：即使旧实例引用被重注册，活跃注册的映射也绝不被旧 close 删除
                ActiveService current = servicesById.get(key.id());
                if (current != null && !current.registration().isActive()) {
                    servicesById.remove(key.id());
                }
            }
        });
        servicesById.put(key.id(), new ActiveService(instance, registration));
        byActivation.computeIfAbsent(activationId, k -> new CopyOnWriteArrayList<>()).add(registration);
        return registration;
    }

    /** 按键查找；未注册或注册已 close 返回 empty。 */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> find(ServiceKey<T> key) {
        Objects.requireNonNull(key, "key");
        // 无锁读：isActive 判定通过后仍可能与并发 close 瞬时交叠（弱一致窗口），不承诺强一致
        ActiveService active = servicesById.get(key.id());
        if (active == null || !active.registration().isActive()) {
            return Optional.empty();
        }
        return Optional.of((T) active.instance());
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

    private static void requireKnownServiceKey(String id) {
        if (!ServiceKeys.ALL.contains(id)) {
            throw new IllegalArgumentException(
                    "unknown service key（docs/extension-points.md 未登记）: " + id);
        }
    }
}
