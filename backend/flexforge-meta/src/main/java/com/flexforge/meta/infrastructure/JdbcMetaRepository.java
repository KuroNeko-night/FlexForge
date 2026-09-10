package com.flexforge.meta.infrastructure;

import com.flexforge.common.api.PageQuery;
import com.flexforge.common.api.PageResult;
import com.flexforge.meta.domain.EntityDefinition;
import com.flexforge.meta.domain.EntityRecord;
import com.flexforge.meta.domain.EntityStatus;
import com.flexforge.meta.domain.FieldDefinition;
import com.flexforge.meta.domain.MetaRepository;
import com.flexforge.meta.domain.ViewDefinition;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * meta_* 表 JDBC 实现：全部参数化（动态部分仅限白名单排序列与 ASC/DESC 字面量，
 * docs/coding-standards §2 数据访问红线）；JSONB 写入用 ?::jsonb 显式转型，读出转文本后解析。
 */
@Repository
public class JdbcMetaRepository implements MetaRepository {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 列表排序白名单（API 字段 → 列名）；sortBy 已由 PageQuery 白名单前置校验。 */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "name", "name",
            "displayName", "display_name",
            "status", "status",
            "updatedAt", "updated_at");

    private static final String ENTITY_COLUMNS =
            "id, name, display_name, status, plugin_id, created_at, updated_at";

    private static final String FIELD_COLUMNS = "id, entity_id, name, display_name, field_type,"
            + " required, default_value, validation, renderer_id, position";

    private static final String VIEW_COLUMNS = "id, entity_id, view_type, name, columns, filters,"
            + " group_by";

    private final RowMapper<EntityRecord> entityRow = this::mapEntity;
    private final RowMapper<FieldDefinition> fieldRow = this::mapField;
    private final RowMapper<ViewDefinition> viewRow = this::mapView;

    private final JdbcTemplate jdbc;

    public JdbcMetaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public EntityRecord insertEntity(String id, String name, String displayName) {
        jdbc.update("INSERT INTO meta_entity (id, name, display_name) VALUES (?, ?, ?)",
                id, name, displayName);
        return findEntity(id).orElseThrow();
    }

    @Override
    public Optional<EntityRecord> findEntity(String id) {
        return jdbc.query("SELECT " + ENTITY_COLUMNS + " FROM meta_entity WHERE id = ?",
                entityRow, id).stream().findFirst();
    }

    @Override
    public Optional<EntityRecord> findEntityByName(String name) {
        return jdbc.query("SELECT " + ENTITY_COLUMNS + " FROM meta_entity WHERE name = ?",
                entityRow, name).stream().findFirst();
    }

    @Override
    public int updateEntity(String id, String name, String displayName, EntityStatus status,
                            boolean requireDraftStatus) {
        // 布尔守卫内嵌 SQL（? 绑定）：requireDraftStatus=true（改名等 breaking 路径）时
        // 仅实体仍为 draft 才生效，把 TOCTOU 窗口关闭在单条 UPDATE 内；
        // 状态迁移路径（rename=false）不设守卫——写目标恒为 enabled/disabled，
        // 并发下最后写入者胜，不会产生非法状态
        return jdbc.update("UPDATE meta_entity SET name = ?, display_name = ?, status = ?,"
                        + " updated_at = now() WHERE id = ? AND (NOT ? OR status = 'draft')",
                name, displayName, status.wireName(), id, requireDraftStatus);
    }

    @Override
    public PageResult<EntityRecord> listEntities(PageQuery query, EntityStatus statusFilter) {
        String where = statusFilter == null ? "" : " WHERE status = ?";
        Object[] filterArgs = statusFilter == null ? new Object[0] : new Object[]{statusFilter.wireName()};
        Long total = jdbc.queryForObject("SELECT count(*) FROM meta_entity" + where, Long.class, filterArgs);

        String order = SORT_COLUMNS.get(query.sortBy() == null ? "updatedAt" : query.sortBy());
        Objects.requireNonNull(order, "sortBy 已过 PageQuery 白名单但缺少列映射（防御纵深，二次强制）");
        String direction = query.sortDirection() == PageQuery.SortDirection.DESC ? "DESC" : "ASC";
        String sql = "SELECT " + ENTITY_COLUMNS + " FROM meta_entity" + where
                + " ORDER BY " + order + " " + direction + " LIMIT ? OFFSET ?";
        Object[] args = join(filterArgs, query.pageSize(), (query.pageNumber() - 1) * query.pageSize());
        List<EntityRecord> items = jdbc.query(sql, entityRow, args);
        return new PageResult<>(items, total == null ? 0 : total, query.pageNumber(), query.pageSize());
    }

    /**
     * 弱一致装配：实体行、字段、视图来自三条独立查询（无共享快照），
     * 极端并发下可能拼出跨版本的混合定义；由 MetaRegistry 写后失效 + 版本比对兜底，
     * 缓存中的定义最终收敛到最新（本类不引入事务读以保证读路径无锁）。
     */
    @Override
    public Optional<EntityDefinition> loadDefinition(String entityId) {
        return findEntity(entityId).map(entity -> new EntityDefinition(
                entity.id(), entity.name(), entity.displayName(), entity.status(), entity.pluginId(),
                loadFields(entityId), loadViews(entityId)));
    }

    private List<FieldDefinition> loadFields(String entityId) {
        return jdbc.query("SELECT " + FIELD_COLUMNS + " FROM meta_field WHERE entity_id = ?"
                + " ORDER BY position, name", fieldRow, entityId);
    }

    private List<ViewDefinition> loadViews(String entityId) {
        return jdbc.query("SELECT " + VIEW_COLUMNS + " FROM meta_view WHERE entity_id = ?"
                + " ORDER BY view_type", viewRow, entityId);
    }

    @Override
    public FieldDefinition insertField(FieldDefinition field) {
        try {
            jdbc.update("INSERT INTO meta_field (" + FIELD_COLUMNS + ")"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)",
                    field.id(), field.entityId(), field.name(), field.displayName(), field.fieldType(),
                    field.required(), jsonOf(field.defaultValue()), jsonOf(field.validation()),
                    field.rendererId(), field.position());
        } catch (DuplicateKeyException e) {
            // 表上仅有 (entity_id, name) 业务唯一约束与主键，此处默认冲突源为前者；
            // 未来新增其他唯一约束时须按约束名区分转写
            throw new DuplicateKeyException("字段名已存在: " + field.name());
        }
        return field;
    }

    @Override
    public Optional<FieldDefinition> findField(String fieldId) {
        return jdbc.query("SELECT " + FIELD_COLUMNS + " FROM meta_field WHERE id = ?",
                fieldRow, fieldId).stream().findFirst();
    }

    @Override
    public int updateField(FieldDefinition field, boolean requireDraftEntity) {
        // draft 守卫用 EXISTS 子查询而非应用层读状态：更新判定与写入在同一条语句内完成，
        // 关闭"读取后实体被并发启用"窗口（语义列变更仅 draft 实体放行）
        return jdbc.update("UPDATE meta_field f SET name = ?, display_name = ?, field_type = ?,"
                        + " required = ?, default_value = ?, validation = ?::jsonb, renderer_id = ?,"
                        + " position = ?, updated_at = now()"
                        + " WHERE f.id = ? AND (NOT ? OR EXISTS (SELECT 1 FROM meta_entity e"
                        + " WHERE e.id = f.entity_id AND e.status = 'draft'))",
                field.name(), field.displayName(), field.fieldType(), field.required(),
                jsonOf(field.defaultValue()), jsonOf(field.validation()), field.rendererId(),
                field.position(), field.id(), requireDraftEntity);
    }

    @Override
    public ViewDefinition insertView(ViewDefinition view) {
        try {
            jdbc.update("INSERT INTO meta_view (" + VIEW_COLUMNS + ")"
                            + " VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)",
                    view.id(), view.entityId(), view.viewType(), view.name(),
                    jsonOf(view.columns()), jsonOf(view.filters()), view.groupBy());
        } catch (DuplicateKeyException e) {
            throw new DuplicateKeyException("该实体已存在同类型视图: " + view.viewType());
        }
        return view;
    }

    @Override
    public Optional<ViewDefinition> findView(String viewId) {
        return jdbc.query("SELECT " + VIEW_COLUMNS + " FROM meta_view WHERE id = ?",
                viewRow, viewId).stream().findFirst();
    }

    @Override
    public int updateView(ViewDefinition view) {
        return jdbc.update("UPDATE meta_view SET name = ?, columns = ?::jsonb, filters = ?::jsonb,"
                        + " group_by = ?, updated_at = now() WHERE id = ?",
                view.name(), jsonOf(view.columns()), jsonOf(view.filters()), view.groupBy(),
                view.id());
    }

    private EntityRecord mapEntity(ResultSet rs, int rowNum) throws SQLException {
        return new EntityRecord(rs.getString("id"), rs.getString("name"),
                rs.getString("display_name"), EntityStatus.fromName(rs.getString("status")),
                rs.getString("plugin_id"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private FieldDefinition mapField(ResultSet rs, int rowNum) throws SQLException {
        return new FieldDefinition(rs.getString("id"), rs.getString("entity_id"),
                rs.getString("name"), rs.getString("display_name"), rs.getString("field_type"),
                rs.getBoolean("required"), parse(rs.getString("default_value")),
                parse(rs.getString("validation")), rs.getString("renderer_id"), rs.getInt("position"));
    }

    private ViewDefinition mapView(ResultSet rs, int rowNum) throws SQLException {
        return new ViewDefinition(rs.getString("id"), rs.getString("entity_id"),
                rs.getString("view_type"), rs.getString("name"),
                parse(rs.getString("columns")), parse(rs.getString("filters")),
                rs.getString("group_by"));
    }

    private static JsonNode parse(String raw) {
        if (raw == null) {
            return null;
        }
        JsonNode node = JSON.readTree(raw);
        // 空对象归一为 null（P25 缺陷修复）：历史插件注册曾把缺省值/校验写成 '{}'，
        // 缺省值 {} 会以对象进入表单预填渲染为 "[object Object]"；空对象与 null
        // 语义等价（无规则/无默认），读侧归一兼容存量行（V017 另行清理数据）
        if (node.isObject() && node.isEmpty()) {
            return null;
        }
        return node;
    }

    private static String jsonOf(JsonNode node) {
        return node == null ? null : node.toString();
    }

    private static Object[] join(Object[] head, Object... tail) {
        Object[] args = new Object[head.length + tail.length];
        System.arraycopy(head, 0, args, 0, head.length);
        System.arraycopy(tail, 0, args, head.length, tail.length);
        return args;
    }
}
