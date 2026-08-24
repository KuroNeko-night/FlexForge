package com.flexforge.data.application;

import com.flexforge.common.PublicApi;
import com.flexforge.common.audit.AuditEventPort;
import com.flexforge.common.audit.AuditEvents;
import com.flexforge.common.api.PageQuery;
import com.flexforge.common.api.PageResult;
import com.flexforge.data.domain.RecordEntry;
import com.flexforge.data.domain.RecordFilter;
import com.flexforge.data.domain.RecordIds;
import com.flexforge.data.domain.RecordRepository;
import com.flexforge.meta.application.MetaRegistry;
import com.flexforge.meta.domain.EntityDefinition;
import com.flexforge.meta.domain.EntityStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * 动态数据读写（service.data-access 落地，登记册 §2.1）：动态 CRUD 唯一路径——
 * 仅启用实体（draft/disabled 一律 404，不泄露存在性）；写入经 RecordValidator
 * 实体级校验编排；删除物理删除并记审计（data.record.create/update/delete，actor=用户名）。
 */
@PublicApi
@Service
public class DynamicRecordService {

    /** 排序白名单的系统列（动态字段之外）。 */
    public static final String SORT_CREATED_AT = "createdAt";
    public static final String SORT_UPDATED_AT = "updatedAt";

    private final MetaRegistry meta;
    private final RecordRepository repository;
    private final AuditEventPort audit;
    private final Clock clock;

    public DynamicRecordService(MetaRegistry meta, RecordRepository repository,
                                AuditEventPort audit, Clock clock) {
        this.meta = meta;
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    /** 新增记录（缺省字段按默认值补齐后校验）。 */
    public RecordEntry create(String actor, String entityName, JsonNode payload) {
        EntityDefinition entity = requireEnabledEntity(entityName);
        RecordEntry record = new RecordEntry(RecordIds.newId(), entity.id(),
                RecordValidator.validateForCreate(entity, payload), null, null);
        RecordEntry inserted = repository.insert(record);
        audit.record(AuditEvents.of(actor, "data.record.create", inserted.id(), "success", clock));
        return inserted;
    }

    /** 记录详情（404 语义：记录或实体不存在/未启用）。 */
    public RecordEntry detail(String entityName, String recordId) {
        EntityDefinition entity = requireEnabledEntity(entityName);
        return repository.find(recordId)
                .filter(record -> record.entityId().equals(entity.id()))
                .orElseThrow(() -> new NoSuchElementException("记录不存在: " + recordId));
    }

    /** 编辑记录（补丁语义：null 清除字段值，合并结果整体校验）。 */
    public RecordEntry update(String actor, String entityName, String recordId, JsonNode patch) {
        EntityDefinition entity = requireEnabledEntity(entityName);
        RecordEntry current = detail(entityName, recordId);
        JsonNode merged = RecordValidator.validatePatch(entity, current.data(), patch);
        if (repository.updateData(recordId, merged) != 1) {
            throw new NoSuchElementException("记录不存在: " + recordId);
        }
        audit.record(AuditEvents.of(actor, "data.record.update", recordId, "success", clock));
        return repository.find(recordId).orElseThrow();
    }

    /** 物理删除记录（审计先行语义同上）。 */
    public void delete(String actor, String entityName, String recordId) {
        EntityDefinition entity = requireEnabledEntity(entityName);
        detail(entityName, recordId);
        if (repository.delete(recordId) != 1) {
            throw new NoSuchElementException("记录不存在: " + recordId);
        }
        audit.record(AuditEvents.of(actor, "data.record.delete", recordId, "success", clock));
    }

    /** 查询载荷（控制器从请求参数组装；filters 为 "字段.操作符" 原始参数）。 */
    @PublicApi
    public record DataQuery(int page, int pageSize, String sortBy,
                            PageQuery.SortDirection direction, java.util.Map<String, String> filters) {
    }

    /** 分页查询：排序白名单 = 实体字段 ∪ 系统列（PageQuery 强制）；过滤条件白名单解析。 */
    public PageResult<RecordEntry> query(String entityName, DataQuery query) {
        EntityDefinition entity = requireEnabledEntity(entityName);
        PageQuery page = PageQuery.of(query.page(), query.pageSize(), query.sortBy(),
                query.direction() == null ? PageQuery.SortDirection.ASC : query.direction(),
                sortWhitelist(entity));
        List<RecordFilter> filters = DataQueryParams.parseFilters(entity, query.filters());
        return repository.query(entity, page, filters);
    }

    /** 排序白名单（查询路径构造 PageQuery 用）。 */
    public static Set<String> sortWhitelist(EntityDefinition entity) {
        Set<String> names = new HashSet<>();
        entity.fields().forEach(field -> names.add(field.name()));
        names.add(SORT_CREATED_AT);
        names.add(SORT_UPDATED_AT);
        return Set.copyOf(names);
    }

    private EntityDefinition requireEnabledEntity(String entityName) {
        EntityDefinition entity = meta.findEntityByName(entityName)
                .orElseThrow(() -> new NoSuchElementException("实体不存在: " + entityName));
        if (entity.status() != EntityStatus.ENABLED) {
            throw new NoSuchElementException("实体不存在: " + entityName);
        }
        return entity;
    }
}
