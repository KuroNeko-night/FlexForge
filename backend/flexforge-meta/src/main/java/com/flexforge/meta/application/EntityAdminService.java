package com.flexforge.meta.application;

import com.flexforge.common.PublicApi;
import com.flexforge.common.audit.AuditEventPort;
import com.flexforge.common.audit.AuditEvents;
import com.flexforge.meta.domain.EntityDefinition;
import com.flexforge.meta.domain.EntityRecord;
import com.flexforge.meta.domain.EntityStatus;
import com.flexforge.meta.domain.FieldDefinition;
import com.flexforge.meta.domain.FieldTypeRegistry;
import com.flexforge.meta.domain.Identifiers;
import com.flexforge.meta.domain.MetaIds;
import com.flexforge.meta.domain.MetaRepository;
import com.flexforge.meta.domain.ViewDefinition;
import com.flexforge.meta.domain.ViewRules;
import com.flexforge.meta.domain.ViewType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Clock;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 元数据写模型（FR-META-01/02/03/05）：实体创建/编辑/停用、字段与视图配置，
 * 全部经 FieldTypeRegistry/ViewRules 白名单校验后落库，写后失效 MetaRegistry 缓存
 * 并记录审计事件（service.audit，actor=操作者用户名）。
 *
 * <p>breaking 口径（docs/09 P04）：非 draft（enabled/disabled）实体上改名/改类型/
 * 改必填/改校验/改默认值一律拒绝——P04 以状态为门（数据表 P05 落地，届时细化
 * "有数据"检测）；displayName/rendererId/position 为表现层可改；新增字段与视图 additive。
 */
@PublicApi
@Service
public class EntityAdminService {

    /** breaking 拒绝消息前缀（测试与诊断共用口径）。 */
    public static final String BREAKING_PREFIX = "breaking 变更被拒绝（非 draft 实体，需迁移方案/ADR，docs/09 P04）: ";

    private final MetaRepository repository;
    private final MetaRegistry registry;
    private final AuditEventPort audit;
    private final Clock clock;

    public EntityAdminService(MetaRepository repository, MetaRegistry registry,
                              AuditEventPort audit, Clock clock) {
        this.repository = repository;
        this.registry = registry;
        this.audit = audit;
        this.clock = clock;
    }

    /** 实体更新命令（PATCH 语义：null = 不变）。 */
    @PublicApi
    public record UpdateEntityCommand(String name, String displayName, String status) {
    }

    /**
     * 字段写入命令（新增全量必填；更新 null = 不变；JSON 显式 null 在 API 边界归一化为未提供）。
     * 组件数对齐 meta_field 写入列（表行镜像口径）。
     */
    @PublicApi
    public record FieldCommand(String name, String displayName, String fieldType, Boolean required,
                               JsonNode defaultValue, JsonNode validation, String rendererId,
                               Integer position) {
    }

    /** 视图写入命令（新增全量必填；更新 null = 不变）；groupBy 仅 kanban（P17）。 */
    @PublicApi
    public record ViewCommand(String viewType, String name, JsonNode columns, JsonNode filters,
                              String groupBy) {
    }

    /** 创建实体（初始 draft）。 */
    public EntityDefinition createEntity(String actor, String name, String displayName) {
        Identifiers.validateName(name, "实体名");
        Identifiers.validateDisplayName(displayName, "实体显示名");
        EntityRecord created;
        try {
            created = repository.insertEntity(MetaIds.newId(), name, displayName);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("实体名已存在: " + name);
        }
        publish(actor, "meta.entity.create", created.id(), created.id());
        return repository.loadDefinition(created.id()).orElseThrow();
    }

    /** 编辑实体（显示名随时可改；改名仅 draft 且更新带 draft 守卫）。 */
    public EntityDefinition updateEntity(String actor, String entityId, UpdateEntityCommand cmd) {
        EntityRecord current = requireEntity(entityId);
        String name = resolveEntityName(current, cmd);
        String displayName = cmd.displayName() == null ? current.displayName() : cmd.displayName();
        Identifiers.validateDisplayName(displayName, "实体显示名");
        EntityStatus status = resolveEntityStatus(current, cmd);
        boolean rename = cmd.name() != null && !cmd.name().equals(current.name());
        int rows;
        try {
            rows = repository.updateEntity(entityId, name, displayName, status, rename);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("实体名已存在: " + name);
        }
        if (rows != 1) {
            // 0 行 = 不存在或并发离开 draft（draft 守卫在 SQL 内），重查区分 400/404
            requireUnchangedOrMissing(repository.findEntity(entityId).isPresent(), "实体改名");
            throw new NoSuchElementException("实体不存在: " + entityId);
        }
        publish(actor, "meta.entity.update", entityId, entityId);
        return repository.loadDefinition(entityId).orElseThrow();
    }

    /** 新增字段（additive：非 draft 实体同样允许）。 */
    public FieldDefinition addField(String actor, String entityId, FieldCommand cmd) {
        EntityDefinition definition = requireDefinition(entityId);
        FieldDefinition field = buildField(MetaIds.newId(), entityId, cmd, definition);
        try {
            repository.insertField(field);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        publish(actor, "meta.field.create", field.id(), entityId);
        return field;
    }

    /** 编辑字段（表现层随时可改；语义列仅 draft 实体可改，更新带 draft 守卫关闭并发启用窗口）。 */
    public FieldDefinition updateField(String actor, String fieldId, FieldCommand cmd) {
        FieldDefinition current = repository.findField(fieldId)
                .orElseThrow(() -> new NoSuchElementException("字段不存在: " + fieldId));
        EntityRecord entity = requireEntity(current.entityId());
        EntityDefinition definition = requireDefinition(current.entityId());
        FieldDefinition merged = FieldChanges.merge(current, cmd, entity, definition);
        int rows;
        try {
            rows = repository.updateField(merged, FieldChanges.isSemanticChange(current, merged));
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        if (rows != 1) {
            // 同 updateEntity：draft 守卫（EXISTS 子查询）在 SQL 内，0 行重查区分 400/404
            requireUnchangedOrMissing(repository.findField(fieldId).isPresent(), "字段语义变更");
            throw new NoSuchElementException("字段不存在: " + fieldId);
        }
        publish(actor, "meta.field.update", fieldId, current.entityId());
        return merged;
    }

    /** 新增视图（additive：非 draft 实体同样允许）。 */
    public ViewDefinition addView(String actor, String entityId, ViewCommand cmd) {
        EntityDefinition definition = requireDefinition(entityId);
        ViewDefinition view = buildView(MetaIds.newId(), entityId, cmd, definition);
        try {
            repository.insertView(view);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        publish(actor, "meta.view.create", view.id(), entityId);
        return view;
    }

    /** 编辑视图（表现层配置，非 draft 实体允许；viewType 不可改）。 */
    public ViewDefinition updateView(String actor, String viewId, ViewCommand cmd) {
        ViewDefinition current = repository.findView(viewId)
                .orElseThrow(() -> new NoSuchElementException("视图不存在: " + viewId));
        requireEntity(current.entityId());
        EntityDefinition definition = requireDefinition(current.entityId());
        if (cmd.viewType() != null && !cmd.viewType().equals(current.viewType())) {
            throw new IllegalArgumentException("viewType 创建后不可改（如需变更请删除后重建同类型视图）");
        }
        String name = cmd.name() == null ? current.name() : cmd.name();
        JsonNode columns = cmd.columns() == null ? current.columns() : cmd.columns();
        JsonNode filters = cmd.filters() == null ? current.filters() : cmd.filters();
        String groupBy = cmd.groupBy() == null ? current.groupBy() : cmd.groupBy();
        ViewType type = ViewType.fromName(current.viewType());
        ViewRules.validate(type, name, columns, filters, fieldNamesOf(definition));
        if (type == ViewType.KANBAN) {
            ViewRules.validateKanban(groupBy, fieldNamesOf(definition),
                    enumFieldNamesOf(definition));
        }
        ViewDefinition merged = new ViewDefinition(current.id(), current.entityId(),
                current.viewType(), name, orEmptyArray(columns), orEmptyArray(filters),
                normalizeGroupBy(type, groupBy));
        if (repository.updateView(merged) != 1) {
            throw new NoSuchElementException("视图不存在: " + viewId);
        }
        publish(actor, "meta.view.update", viewId, current.entityId());
        return merged;
    }

    /**
     * 写后失效 + 审计。失效必须在仓储写入已落库后执行（本服务仓储各方法独立自动提交，
     * 无外层事务），保证 MetaRegistry 下次装载读到的是新定义；审计在失效之后记录，
     * 两者不原子——写成功而审计失败时数据已生效但缺审计事件（可接受，见审计发现）。
     */
    private void publish(String actor, String action, String objectId, String entityId) {
        registry.evict(entityId);
        audit.record(AuditEvents.of(actor, action, objectId, "success", clock));
    }

    private EntityRecord requireEntity(String entityId) {
        return repository.findEntity(entityId)
                .orElseThrow(() -> new NoSuchElementException("实体不存在: " + entityId));
    }

    private EntityDefinition requireDefinition(String entityId) {
        return repository.loadDefinition(entityId)
                .orElseThrow(() -> new NoSuchElementException("实体不存在: " + entityId));
    }

    private String resolveEntityName(EntityRecord current, UpdateEntityCommand cmd) {
        if (cmd.name() == null || cmd.name().equals(current.name())) {
            return current.name();
        }
        requireDraft(current, "实体改名");
        Identifiers.validateName(cmd.name(), "实体名");
        // 预检只为友好报错；"查名与更新"之间的并发抢名由 meta_entity 唯一约束兜底（下方 catch）
        boolean nameTaken = repository.findEntityByName(cmd.name())
                .filter(other -> !other.id().equals(current.id())).isPresent();
        if (nameTaken) {
            throw new IllegalArgumentException("实体名已存在: " + cmd.name());
        }
        return cmd.name();
    }

    private EntityStatus resolveEntityStatus(EntityRecord current, UpdateEntityCommand cmd) {
        if (cmd.status() == null || current.status().wireName().equals(cmd.status())) {
            return current.status();
        }
        EntityStatus target = EntityStatus.fromName(cmd.status());
        if (!current.status().canTransitionTo(target)) {
            throw new IllegalArgumentException(
                    "非法状态迁移: " + current.status().wireName() + " → " + cmd.status());
        }
        return target;
    }

    private FieldDefinition buildField(String id, String entityId, FieldCommand cmd,
                                       EntityDefinition definition) {
        Identifiers.validateName(cmd.name(), "字段名");
        Identifiers.validateDisplayName(cmd.displayName(), "字段显示名");
        FieldTypeRegistry.FieldType type = FieldTypeRegistry.require(cmd.fieldType());
        FieldTypeRegistry.validateRules(type, cmd.validation());
        FieldTypeRegistry.validateDefaultValue(type, cmd.defaultValue(), cmd.validation());
        if (definition.fields().stream().anyMatch(f -> f.name().equals(cmd.name()))) {
            throw new IllegalArgumentException("字段名已存在: " + cmd.name());
        }
        return new FieldDefinition(id, entityId, cmd.name(), cmd.displayName(), cmd.fieldType(),
                Boolean.TRUE.equals(cmd.required()), cmd.defaultValue(),
                orEmptyObject(cmd.validation()),
                FieldChanges.rendererOf(type, cmd.rendererId()),
                FieldChanges.positionOf(definition, cmd.position()));
    }

    /** 更新守卫失败时区分"对象还在（并发状态变化→breaking）"与"对象已不存在（404）"。 */
    private static void requireUnchangedOrMissing(boolean stillExists, String operation) {
        if (stillExists) {
            throw new IllegalArgumentException(BREAKING_PREFIX + operation
                    + "（更新期间实体状态已变化，请刷新后重试）");
        }
    }

    private ViewDefinition buildView(String id, String entityId, ViewCommand cmd,
                                     EntityDefinition definition) {
        ViewType type = ViewType.fromName(cmd.viewType());
        Identifiers.validateDisplayName(cmd.name(), "视图名");
        ViewRules.validate(type, cmd.name(), cmd.columns(), cmd.filters(), fieldNamesOf(definition));
        if (type == ViewType.KANBAN) {
            ViewRules.validateKanban(cmd.groupBy(), fieldNamesOf(definition),
                    enumFieldNamesOf(definition));
        }
        return new ViewDefinition(id, entityId, type.wireName(), cmd.name(),
                orEmptyArray(cmd.columns()), orEmptyArray(cmd.filters()),
                normalizeGroupBy(type, cmd.groupBy()));
    }

    /** groupBy 仅 kanban 视图持久化（其余归 null，与插件注册路径同口径，审查 P2-1）。 */
    private static String normalizeGroupBy(ViewType type, String groupBy) {
        return type == ViewType.KANBAN ? groupBy : null;
    }

    private static Set<String> fieldNamesOf(EntityDefinition definition) {
        return definition.fields().stream().map(FieldDefinition::name).collect(Collectors.toSet());
    }

    /** enum 字段名集合（kanban groupBy 校验用，P17；类型判断经 registry 单点）。 */
    private static Set<String> enumFieldNamesOf(EntityDefinition definition) {
        return definition.fields().stream()
                .filter(field -> FieldTypeRegistry.require(field.fieldType())
                        == FieldTypeRegistry.FieldType.ENUM)
                .map(FieldDefinition::name)
                .collect(Collectors.toSet());
    }

    private static void requireDraft(EntityRecord entity, String operation) {
        if (entity.status() != EntityStatus.DRAFT) {
            throw new IllegalArgumentException(BREAKING_PREFIX + operation
                    + "（当前状态 " + entity.status().wireName() + "）");
        }
    }

    private static JsonNode orEmptyObject(JsonNode node) {
        return node == null ? JsonNodeFactory.instance.objectNode() : node;
    }

    private static JsonNode orEmptyArray(JsonNode node) {
        return node == null ? JsonNodeFactory.instance.arrayNode() : node;
    }
}
