package com.flexforge.runtime;

import com.flexforge.common.PublicApi;
import com.flexforge.common.contract.DomainEvent;
import com.flexforge.common.contract.Registration;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 内存领域事件发布器（event.domain，docs/extension-points.md §2.3）：同步顺序通知活跃监听器。
 *
 * <p>监听器契约：不得抛出异常；抛出将中断并向上传播给 publish 调用方（MVP 冻结口径，
 * 异常隔离策略如需调整走 ADR）。closeAll 按激活身份整体撤销监听（NFR-PLUGIN-01）。
 */
@PublicApi
public final class InMemoryDomainEventPublisher {

    /** 监听器持有者：依赖身份相等（默认 equals）移除，避免 equals 相同的重复监听被误删。 */
    private static final class Holder {
        private final Consumer<DomainEvent> listener;

        Holder(Consumer<DomainEvent> listener) {
            this.listener = listener;
        }
    }

    private final CopyOnWriteArrayList<Holder> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, List<SimpleRegistration>> byActivation = new ConcurrentHashMap<>();

    /** 订阅领域事件；返回可撤销注册项。 */
    public synchronized Registration subscribe(Consumer<DomainEvent> listener, String activationId) {
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(activationId, "activationId");
        Holder holder = new Holder(listener);
        listeners.add(holder);
        SimpleRegistration registration = new SimpleRegistration(activationId, () -> listeners.remove(holder));
        byActivation.computeIfAbsent(activationId, k -> new CopyOnWriteArrayList<>()).add(registration);
        return registration;
    }

    /** 向全部活跃监听器同步发布事件（注册顺序）。 */
    public void publish(DomainEvent event) {
        Objects.requireNonNull(event, "event");
        listeners.forEach(holder -> holder.listener.accept(event));
    }

    /** 撤销某激活身份下的全部监听注册；幂等，返回被撤销的注册项快照。 */
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
