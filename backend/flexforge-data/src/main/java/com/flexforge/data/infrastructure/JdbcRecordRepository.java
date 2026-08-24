package com.flexforge.data.infrastructure;

import com.flexforge.common.api.PageQuery;
import com.flexforge.common.api.PageResult;
import com.flexforge.data.domain.RecordEntry;
import com.flexforge.data.domain.RecordFilter;
import com.flexforge.data.domain.RecordRepository;
import com.flexforge.meta.domain.EntityDefinition;
import com.flexforge.meta.domain.FieldDefinition;
import com.flexforge.meta.domain.FieldTypeRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * data_record 表 JDBC 实现（service.data-access 唯一存储路径，RB-DATA 单点断言目标）：
 * SQL 中的动态片段只有——已通过白名单的 snake_case 字段名（防御性再校验）、
 * FieldTypeRegistry 契约的类型转换与固定操作符模板；值一律参数化绑定（NFR-SEC-02）。
 */
@Repository
public class JdbcRecordRepository implements RecordRepository {

    /** 字段名防御性再校验（应用层已白名单，此处防纵深，docs/13 §4）。 */
    private static final Pattern SAFE_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    private static final Map<String, String> SYSTEM_SORT =
            Map.of("createdAt", "created_at", "updatedAt", "updated_at");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String COLUMNS = "id, entity_id, data, created_at, updated_at";

    private final RowMapper<RecordEntry> row = this::mapRow;

    private final JdbcTemplate jdbc;

    public JdbcRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RecordEntry insert(RecordEntry record) {
        jdbc.update("INSERT INTO data_record (id, entity_id, data) VALUES (?, ?, ?::jsonb)",
                record.id(), record.entityId(), record.data() == null ? "{}" : record.data().toString());
        return find(record.id()).orElseThrow();
    }

    @Override
    public Optional<RecordEntry> find(String recordId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM data_record WHERE id = ?", row, recordId)
                .stream().findFirst();
    }

    @Override
    public int updateData(String recordId, JsonNode data) {
        return jdbc.update("UPDATE data_record SET data = ?::jsonb, updated_at = now() WHERE id = ?",
                data.toString(), recordId);
    }

    @Override
    public int delete(String recordId) {
        return jdbc.update("DELETE FROM data_record WHERE id = ?", recordId);
    }

    @Override
    public PageResult<RecordEntry> query(EntityDefinition entity, PageQuery page,
                                         List<RecordFilter> filters) {
        List<String> clauses = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        clauses.add("entity_id = ?");
        args.add(entity.id());
        for (RecordFilter filter : filters) {
            clauses.add(conditionOf(filter));
            args.add(argumentOf(filter));
        }
        String where = String.join(" AND ", clauses);

        Long total = jdbc.queryForObject("SELECT count(*) FROM data_record WHERE " + where,
                Long.class, args.toArray());
        String order = orderExpression(entity, page);
        String sql = "SELECT " + COLUMNS + " FROM data_record WHERE " + where
                + " ORDER BY " + order + (page.sortDirection() == PageQuery.SortDirection.DESC ? " DESC" : " ASC")
                + " NULLS LAST LIMIT ? OFFSET ?";
        args.add(page.pageSize());
        args.add((page.pageNumber() - 1) * page.pageSize());
        List<RecordEntry> items = jdbc.query(sql, row, args.toArray());
        return new PageResult<>(items, total == null ? 0 : total, page.pageNumber(), page.pageSize());
    }

    /** 排序表达式：系统列直接列名；动态字段经 FieldTypeRegistry 类型转换保序。 */
    private String orderExpression(EntityDefinition entity, PageQuery page) {
        if (page.sortBy() == null) {
            return "created_at";
        }
        String system = SYSTEM_SORT.get(page.sortBy());
        if (system != null) {
            return system;
        }
        FieldDefinition field = entity.fields().stream()
                .filter(f -> f.name().equals(page.sortBy())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "sortBy 已过白名单但实体无此字段: " + page.sortBy()));
        return castExpression(field.name(), FieldTypeRegistry.require(field.fieldType()));
    }

    private String conditionOf(RecordFilter filter) {
        String extract = castExpression(filter.field(), filter.type());
        return switch (filter.operator()) {
            case "eq" -> extract + " = ?";
            case "contains" -> rawExpression(filter.field()) + " ILIKE ? ESCAPE '\\'";
            case "gte" -> extract + " >= ?";
            case "lte" -> extract + " <= ?";
            default -> throw new IllegalArgumentException("操作符已过白名单但无 SQL 模板: " + filter.operator());
        };
    }

    private static Object argumentOf(RecordFilter filter) {
        if ("contains".equals(filter.operator())) {
            String value = String.valueOf(filter.value())
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            return "%" + value + "%";
        }
        return filter.value();
    }

    /** JSONB 提取 + 类型转换：文本类直接文本比较，其余按 registry SQL 类型转换。 */
    private static String castExpression(String field, FieldTypeRegistry.FieldType type) {
        String sqlType = FieldTypeRegistry.contract(type).sqlType();
        if ("TEXT".equals(sqlType) || sqlType.startsWith("VARCHAR")) {
            return rawExpression(field);
        }
        return "(" + rawExpression(field) + ")::" + sqlType;
    }

    private static String rawExpression(String field) {
        if (!SAFE_NAME.matcher(field).matches()) {
            throw new IllegalArgumentException("字段名未通过防御性校验: " + field);
        }
        return "data->>'" + field + "'";
    }

    private RecordEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new RecordEntry(rs.getString("id"), rs.getString("entity_id"),
                parse(rs.getString("data")), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static JsonNode parse(String raw) {
        return raw == null ? null : JSON.readTree(raw);
    }
}
