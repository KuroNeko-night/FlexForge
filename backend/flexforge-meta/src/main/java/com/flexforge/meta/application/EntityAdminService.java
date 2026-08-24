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

    /** 字段写入命令（新增全量必填；更新 null = 不变）。组件数对齐 meta_field 写入列（表行镜像口径）。 */
    @PublicApi
    public record FieldCommand(String name, String displayName, String fieldType, Boolean required,
                               JsonNode defaultValue, JsonNode validation, String rendererId,
                               Integer position) {
    }

    /** 视图写入命令（新增全量必填；更新 null = 不变）。 */
    @PublicApi
    public record ViewCommand(String viewType, String name, JsonNode columns, JsonNode filters) {
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

    /** 编辑实体（显示名随时可改；改名仅 draft；状态按合法迁移）。 */
    public EntityDefinition updateEntity(String actor, String entityId, UpdateEntityCommand cmd) {
        EntityRecord current = requireEntity(entityId);
        String name = resolveEntityName(current, cmd);
        String displayName = cmd.displayName() == null ? current.displayName() : cmd.displayName();
        Identifiers.validateDisplayName(displayName, "实体显示名");
        EntityStatus status = resolveEntityStatus(current, cmd);
        if (repository.updateEntity(entityId, name, displayName, status) != 1) {
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

    /** 编辑字段（表现层随时可改；语义列仅 draft 实体可改）。 */
    public FieldDefinition updateField(String actor, String fieldId, FieldCommand cmd) {
        FieldDefinition current = repository.findField(fieldId)
                .orElseThrow(() -> new NoSuchElementException("字段不存在: " + fieldId));
        EntityRecord entity = requireEntity(current.entityId());
        EntityDefinition definition = requireDefinition(current.entityId());
        FieldDefinition merged = mergeField(current, cmd, entity, definition);
        if (repository.updateField(merged) != 1) {
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
        ViewRules.validate(ViewType.fromName(current.viewType()), name, columns, filters,
                fieldNamesOf(definition));
        ViewDefinition merged = new ViewDefinition(current.id(), current.entityId(),
                current.viewType(), name, orEmptyArray(columns), orEmptyArray(filters));
        if (repository.updateView(merged) != 1) {
            throw new NoSuchElementException("视图不存在: " + viewId);
        }
        publish(actor, "meta.view.update", viewId, current.entityId());
        return merged;
    }

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
                orEmptyObject(cmd.validation()), rendererOf(type, cmd.rendererId()),
                positionOf(definition, cmd.position()));
    }

    private FieldDefinition mergeField(FieldDefinition current, FieldCommand cmd,
                                       EntityRecord entity, EntityDefinition definition) {
        String name = mergedName(current, cmd, entity, definition);
        String fieldType = mergedSemantics(current.fieldType(), cmd.fieldType(), entity, "字段改类型");
        JsonNode validation = mergedJson(current.validation(), cmd.validation(), entity, "改校验规则");
        JsonNode defaultValue = mergedJson(current.defaultValue(), cmd.defaultValue(), entity, "改默认值");
        boolean required = mergedRequired(current, cmd, entity);
        String displayName = cmd.displayName() == null ? current.displayName() : cmd.displayName();
        Identifiers.validateDisplayName(displayName, "字段显示名");
        String rendererId = mergedRenderer(current, cmd);
        int position = cmd.position() == null ? current.position() : cmd.position();
        FieldTypeRegistry.FieldType type = FieldTypeRegistry.require(fieldType);
        FieldTypeRegistry.validateRules(type, validation);
        FieldTypeRegistry.validateDefaultValue(type, defaultValue, validation);
        return new FieldDefinition(current.id(), current.entityId(), name, displayName, fieldType,
                required, defaultValue, validation, rendererId, position);
    }

    private String mergedName(FieldDefinition current, FieldCommand cmd,
                              EntityRecord entity, EntityDefinition definition) {
        if (cmd.name() == null || cmd.name().equals(current.name())) {
            return current.name();
        }
        requireDraft(entity, "字段改名");
        Identifiers.validateName(cmd.name(), "字段名");
        boolean taken = definition.fields().stream()
                .anyMatch(f -> !f.id().equals(current.id()) && f.name().equals(cmd.name()));
        if (taken) {
            throw new IllegalArgumentException("字段名已存在: " + cmd.name());
        }
        boolean referenced = definition.views().stream()
                .anyMatch(view -> viewReferences(view, current.name()));
        if (referenced) {
            throw new IllegalArgumentException(
                    "字段被视图引用，先更新视图引用后再改名: " + current.name());
        }
        return cmd.name();
    }

    private String mergedSemantics(String current, String requested, EntityRecord entity, String label) {
        if (requested == null || requested.equals(current)) {
            return current;
        }
        requireDraft(entity, label);
        return requested;
    }

    private JsonNode mergedJson(JsonNode current, JsonNode requested, EntityRecord entity, String label) {
        if (requested == null || requested.equals(current)) {
            return current;
        }
        requireDraft(entity, label);
        return requested;
    }

    private boolean mergedRequired(FieldDefinition current, FieldCommand cmd, EntityRecord entity) {
        if (cmd.required() == null || cmd.required() == current.required()) {
            return current.required();
        }
        requireDraft(entity, "改字段必填");
        return cmd.required();
    }

    private String mergedRenderer(FieldDefinition current, FieldCommand cmd) {
        if (cmd.rendererId() == null || cmd.rendererId().equals(current.rendererId())) {
            return current.rendererId();
        }
        if (!FieldTypeRegistry.isBuiltInRendererId(cmd.rendererId())) {
            throw new IllegalArgumentException("rendererId 必须是平台内置 ID: " + cmd.rendererId());
        }
        return cmd.rendererId();
    }

    private ViewDefinition buildView(String id, String entityId, ViewCommand cmd,
                                     EntityDefinition definition) {
        ViewType type = ViewType.fromName(cmd.viewType());
        Identifiers.validateDisplayName(cmd.name(), "视图名");
        ViewRules.validate(type, cmd.name(), cmd.columns(), cmd.filters(), fieldNamesOf(definition));
        return new ViewDefinition(id, entityId, type.wireName(), cmd.name(),
                orEmptyArray(cmd.columns()), orEmptyArray(cmd.filters()));
    }

    private static Set<String> fieldNamesOf(EntityDefinition definition) {
        return definition.fields().stream().map(FieldDefinition::name).collect(Collectors.toSet());
    }

    private static boolean viewReferences(ViewDefinition view, String fieldName) {
        return references(view.columns(), fieldName) || references(view.filters(), fieldName);
    }

    private static boolean references(JsonNode items, String fieldName) {
        if (items == null || !items.isArray()) {
            return false;
        }
        for (JsonNode item : items) {
            JsonNode field = item.get("field");
            if (field != null && field.isTextual() && fieldName.equals(field.asText())) {
                return true;
            }
        }
        return false;
    }

    private static String rendererOf(FieldTypeRegistry.FieldType type, String rendererId) {
        if (rendererId == null) {
            return FieldTypeRegistry.contract(type).defaultRendererId();
        }
        if (!FieldTypeRegistry.isBuiltInRendererId(rendererId)) {
            throw new IllegalArgumentException("rendererId 必须是平台内置 ID: " + rendererId);
        }
        return rendererId;
    }

    private static int positionOf(EntityDefinition definition, Integer position) {
        if (position != null) {
            return position;
        }
        return definition.fields().stream().mapToInt(FieldDefinition::position).max().orElse(0) + 1;
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
