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
import java.util.Optional;

/**
 * meta_* 表 JDBC 实现：全部参数化（动态部分仅限白名单排序列与 ASC/DESC 字面量，
 * docs/coding-standards §5.4）；JSONB 写入用 ?::jsonb 显式转型，读出转文本后解析。
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

    private static final String VIEW_COLUMNS = "id, entity_id, view_type, name, columns, filters";

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
    public int updateEntity(String id, String name, String displayName, EntityStatus status) {
        return jdbc.update("UPDATE meta_entity SET name = ?, display_name = ?, status = ?,"
                        + " updated_at = now() WHERE id = ?",
                name, displayName, status.wireName(), id);
    }

    @Override
    public PageResult<EntityRecord> listEntities(PageQuery query, EntityStatus statusFilter) {
        String where = statusFilter == null ? "" : " WHERE status = ?";
        Object[] filterArgs = statusFilter == null ? new Object[0] : new Object[]{statusFilter.wireName()};
        Long total = jdbc.queryForObject("SELECT count(*) FROM meta_entity" + where, Long.class, filterArgs);

        String order = SORT_COLUMNS.get(query.sortBy() == null ? "updatedAt" : query.sortBy());
        String direction = query.sortDirection() == PageQuery.SortDirection.DESC ? "DESC" : "ASC";
        String sql = "SELECT " + ENTITY_COLUMNS + " FROM meta_entity" + where
                + " ORDER BY " + order + " " + direction + " LIMIT ? OFFSET ?";
        Object[] args = join(filterArgs, query.pageSize(), (query.pageNumber() - 1) * query.pageSize());
        List<EntityRecord> items = jdbc.query(sql, entityRow, args);
        return new PageResult<>(items, total == null ? 0 : total, query.pageNumber(), query.pageSize());
    }

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
    public int updateField(FieldDefinition field) {
        return jdbc.update("UPDATE meta_field SET name = ?, display_name = ?, field_type = ?,"
                        + " required = ?, default_value = ?, validation = ?::jsonb, renderer_id = ?,"
                        + " position = ?, updated_at = now() WHERE id = ?",
                field.name(), field.displayName(), field.fieldType(), field.required(),
                jsonOf(field.defaultValue()), jsonOf(field.validation()), field.rendererId(),
                field.position(), field.id());
    }

    @Override
    public ViewDefinition insertView(ViewDefinition view) {
        try {
            jdbc.update("INSERT INTO meta_view (" + VIEW_COLUMNS + ") VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb)",
                    view.id(), view.entityId(), view.viewType(), view.name(),
                    jsonOf(view.columns()), jsonOf(view.filters()));
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
                        + " updated_at = now() WHERE id = ?",
                view.name(), jsonOf(view.columns()), jsonOf(view.filters()), view.id());
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
                parse(rs.getString("columns")), parse(rs.getString("filters")));
    }

    private static JsonNode parse(String raw) {
        return raw == null ? null : JSON.readTree(raw);
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
