package com.flexforge.meta.application;

import com.flexforge.common.PublicApi;
import com.flexforge.common.api.PageQuery;
import com.flexforge.common.api.PageResult;
import com.flexforge.meta.domain.EntityDefinition;
import com.flexforge.meta.domain.EntityRecord;
import com.flexforge.meta.domain.EntityStatus;
import com.flexforge.meta.domain.MetaRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 元数据查询与缓存（service.meta 落地，登记册 §2.1）：实体完整定义按 id 进程内缓存，
 * 任何元数据写入后由写路径调用 {@link #evict} 失效（单节点即时一致）；
 * 版本号随每次失效单调递增，前端携带版本对比即可感知元数据变更并重新拉取（P06 消费）。
 *
 * <p>P08 插件安装/停用通过 {@link #evictAll} 全量失效。列表查询直达存储（分页白名单已强制）。
 */
@PublicApi
@Component
public class MetaRegistry {

    private final MetaRepository repository;
    private final ConcurrentHashMap<String, EntityDefinition> cache = new ConcurrentHashMap<>();
    private final AtomicLong version = new AtomicLong();

    public MetaRegistry(MetaRepository repository) {
        this.repository = repository;
    }

    /** 按实体 ID 取完整定义（缓存命中优先；miss 时装配并缓存）。 */
    public Optional<EntityDefinition> findEntity(String entityId) {
        EntityDefinition cached = cache.get(entityId);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<EntityDefinition> loaded = repository.loadDefinition(entityId);
        loaded.ifPresent(definition -> cache.put(entityId, definition));
        return loaded;
    }

    /** 分页列实体；statusFilter 非 null 时按状态过滤（USER 读路径仅 enabled）。 */
    public PageResult<EntityRecord> listEntities(PageQuery query, EntityStatus statusFilter) {
        return repository.listEntities(query, statusFilter);
    }

    /** 当前元数据版本（每次失效 +1；响应携带供前端比对）。 */
    public long version() {
        return version.get();
    }

    /** 失效单实体缓存并递增版本（写路径与 P08 安装流程调用）。 */
    public void evict(String entityId) {
        cache.remove(entityId);
        version.incrementAndGet();
    }

    /** 全量失效并递增版本（P08 插件安装/停用、批量元数据导入后调用）。 */
    public void evictAll() {
        cache.clear();
        version.incrementAndGet();
    }
}
