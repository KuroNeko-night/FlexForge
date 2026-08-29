package com.flexforge.runtime;

import com.flexforge.common.PublicApi;
import com.flexforge.common.contract.Registration;
import com.flexforge.common.registry.ExtensionPoints;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存扩展点贡献注册表（extension.* 扩展点）：只接受登记册中的 ExtensionPoint ID，
 * 未登记 ID 在注册与读取时均拒绝；贡献按注册顺序快照返回；closeAll 按激活身份整体撤销。
 */
@PublicApi
public final class InMemoryExtensionRegistry {

    /** 贡献持有者：依赖身份相等（默认 equals）移除，避免 equals 相同的重复贡献被误删。 */
    private static final class Holder {
        private final Object value;

        Holder(Object value) {
            this.value = value;
        }
    }

    // CHM + COW：读路径 contributions() 无锁遍历（COW 迭代即稳定快照），写路径在 synchronized 内串行
    private final Map<String, CopyOnWriteArrayList<Holder>> contributionsByPoint = new ConcurrentHashMap<>();
    private final Map<String, List<SimpleRegistration>> byActivation = new ConcurrentHashMap<>();

    /**
     * 登记一份贡献；extensionPointId 必须在 docs/extension-points.md 登记册 active 集合内，
     * 否则抛 IllegalArgumentException（失败路径）。
     */
    public synchronized Registration register(String extensionPointId, Object contribution, String activationId) {
        Objects.requireNonNull(extensionPointId, "extensionPointId");
        Objects.requireNonNull(contribution, "contribution");
        Objects.requireNonNull(activationId, "activationId");
        requireKnownPoint(extensionPointId);
        CopyOnWriteArrayList<Holder> holders =
                contributionsByPoint.computeIfAbsent(extensionPointId, k -> new CopyOnWriteArrayList<>());
        Holder holder = new Holder(contribution);
        SimpleRegistration registration = new SimpleRegistration(activationId, () -> holders.remove(holder));
        byActivation.computeIfAbsent(activationId, k -> new CopyOnWriteArrayList<>()).add(registration);
        holders.add(holder);
        return registration;
    }

    /** 读取某扩展点的全部活跃贡献（注册顺序快照）；未知扩展点 ID 直接拒绝。 */
    public List<Object> contributions(String extensionPointId) {
        requireKnownPoint(extensionPointId);
        List<Holder> holders = contributionsByPoint.get(extensionPointId);
        if (holders == null) {
            return List.of();
        }
        return holders.stream().map(h -> h.value).toList();
    }

    /** 读取某扩展点指定类型的活跃贡献（过滤 + 类型收敛）。 */
    public <T> List<T> contributions(String extensionPointId, Class<T> type) {
        return contributions(extensionPointId).stream().filter(type::isInstance).map(type::cast).toList();
    }

    /** 撤销某激活身份下的全部贡献注册；幂等，返回被撤销的注册项快照。 */
    public synchronized List<Registration> closeAll(String activationId) {
        Objects.requireNonNull(activationId, "activationId");
        List<SimpleRegistration> registrations = byActivation.remove(activationId);
        if (registrations == null) {
            return List.of();
        }
        registrations.forEach(SimpleRegistration::close);
        return List.copyOf(registrations);
    }

    private static void requireKnownPoint(String extensionPointId) {
        if (!ExtensionPoints.ALL.contains(extensionPointId)) {
            throw new IllegalArgumentException(
                    "unknown extension point（docs/extension-points.md 未登记）: " + extensionPointId);
        }
    }
}
