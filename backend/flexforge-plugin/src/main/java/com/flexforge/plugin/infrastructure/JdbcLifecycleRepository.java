package com.flexforge.plugin.infrastructure;

import com.flexforge.plugin.domain.ActivationRecord;
import com.flexforge.plugin.domain.ActivationStatus;
import com.flexforge.plugin.domain.LifecycleRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** plugin_activation/registration/migration JDBC 实现。 */
@Repository
public class JdbcLifecycleRepository implements LifecycleRepository {

    private final RowMapper<ActivationRecord> activationRow = this::mapActivation;
    private final JdbcTemplate jdbc;

    public JdbcLifecycleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ActivationRecord> findActivation(String activationId) {
        return jdbc.query(activationSelect() + " WHERE id = ?", activationRow, activationId)
                .stream().findFirst();
    }

    @Override
    public Optional<ActivationRecord> findOccupying(String pluginId) {
        return jdbc.query(activationSelect()
                        + " WHERE plugin_id = ? AND status IN ('STARTING','ACTIVE')"
                        + " ORDER BY started_at DESC LIMIT 1",
                activationRow, pluginId).stream().findFirst();
    }

    @Override
    public Optional<ActivationRecord> findActiveOperation(String pluginVersionId, String operation) {
        return jdbc.query(activationSelect()
                        + " WHERE plugin_version_id = ? AND operation = ?"
                        + " AND status IN ('STARTING','ACTIVE') LIMIT 1",
                activationRow, pluginVersionId, operation).stream().findFirst();
    }

    @Override
    public Optional<ActivationRecord> findSucceeded(String pluginVersionId, String operation) {
        return jdbc.query(activationSelect()
                        + " WHERE plugin_version_id = ? AND operation = ?"
                        + " AND status IN ('ACTIVE','STOPPED') ORDER BY started_at DESC LIMIT 1",
                activationRow, pluginVersionId, operation).stream().findFirst();
    }

    @Override
    public ActivationRecord insertActivation(String id, String pluginId, String pluginVersionId,
                                             String operation, String requestedBy) {
        jdbc.update("INSERT INTO plugin_activation (id, plugin_id, plugin_version_id, operation,"
                        + " status, requested_by) VALUES (?, ?, ?, ?, 'STARTING', ?)",
                id, pluginId, pluginVersionId, operation, requestedBy);
        return findActivation(id).orElseThrow();
    }

    @Override
    public int updateStatus(String activationId, ActivationStatus status, String stage,
                            String errorCode) {
        return jdbc.update("UPDATE plugin_activation SET status = ?, stage = ?, error_code = ?,"
                        + " finished_at = CASE WHEN ? IN ('ACTIVE','STOPPED','FAILED') THEN now()"
                        + " ELSE finished_at END WHERE id = ?",
                status.wireName(), stage, errorCode, status.wireName(), activationId);
    }

    @Override
    public List<ActivationRecord> findActiveByPlugin(String pluginId) {
        return jdbc.query(activationSelect() + " WHERE plugin_id = ? AND status = 'ACTIVE'",
                activationRow, pluginId);
    }

    @Override
    public List<ActivationRecord> findAllActive() {
        return jdbc.query(activationSelect() + " WHERE status = 'ACTIVE'", activationRow);
    }

    @Override
    public List<ActivationRecord> activationsOf(String pluginId) {
        return jdbc.query(activationSelect()
                        + " WHERE plugin_id = ? ORDER BY started_at DESC LIMIT 20",
                activationRow, pluginId);
    }

    @Override
    public int insertRegistration(String activationId, String extensionType, String registrationKey,
                                  String payloadJson) {
        return jdbc.update("INSERT INTO plugin_registration (id, activation_id, extension_type,"
                        + " registration_key, payload_json) VALUES (?, ?, ?, ?, ?::jsonb)"
                        + " ON CONFLICT (activation_id, extension_type, registration_key) DO NOTHING",
                "pr-" + UUID.randomUUID(), activationId, extensionType, registrationKey, payloadJson);
    }

    @Override
    public List<RegistrationEntry> registrationsOf(String activationId) {
        return jdbc.query("SELECT activation_id, extension_type, registration_key, payload_json"
                        + " FROM plugin_registration WHERE activation_id = ?",
                (rs, n) -> new RegistrationEntry(rs.getString("activation_id"),
                        rs.getString("extension_type"), rs.getString("registration_key"),
                        rs.getString("payload_json")), activationId);
    }

    @Override
    public String resourcePayloadsOf(String pluginVersionId) {
        return jdbc.queryForObject(
                "SELECT resource_payloads FROM plugin_version WHERE id = ?",
                String.class, pluginVersionId);
    }

    @Override
    public String assetPayloadsOf(String pluginVersionId) {
        return jdbc.queryForObject(
                "SELECT asset_payloads FROM plugin_version WHERE id = ?",
                String.class, pluginVersionId);
    }

    @Override
    public int deleteRegistrations(String activationId) {
        return jdbc.update("DELETE FROM plugin_registration WHERE activation_id = ?", activationId);
    }

    @Override
    public int insertMigration(String pluginVersionId, String activationId, String scriptName,
                               String checksum) {
        return jdbc.update("INSERT INTO plugin_migration (id, plugin_version_id, activation_id,"
                        + " script_name, checksum) VALUES (?, ?, ?, ?, ?)"
                        + " ON CONFLICT (plugin_version_id, script_name) DO NOTHING",
                "pm-" + UUID.randomUUID(), pluginVersionId, activationId, scriptName, checksum);
    }

    @Override
    public List<MigrationEntry> migrationsOf(String pluginVersionId) {
        return jdbc.query("SELECT plugin_version_id, activation_id, script_name, checksum"
                        + " FROM plugin_migration WHERE plugin_version_id = ?",
                (rs, n) -> new MigrationEntry(rs.getString("plugin_version_id"),
                        rs.getString("activation_id"), rs.getString("script_name"),
                        rs.getString("checksum")), pluginVersionId);
    }

    @Override
    @Transactional
    public String insertEntityWithFields(String entityName, String displayName, String pluginId,
                                         List<FieldSpec> fields) {
        String entityId = "meta-" + UUID.randomUUID();
        // 同名实体 upsert 仅限本插件归属（WHERE 守卫，Issue #22-2 TOCTOU）：
        // 并发跨归属插入不更新，随后按 name+plugin 复查为空即抛注册冲突
        jdbc.update("INSERT INTO meta_entity (id, name, display_name, status, plugin_id)"
                + " VALUES (?, ?, ?, 'enabled', ?)"
                + " ON CONFLICT (name) DO UPDATE SET status = 'enabled',"
                + " display_name = EXCLUDED.display_name, plugin_id = EXCLUDED.plugin_id,"
                + " updated_at = now()"
                + " WHERE meta_entity.plugin_id = EXCLUDED.plugin_id",
                entityId, entityName, displayName, pluginId);
        String actualId = jdbc.queryForObject(
                "SELECT id FROM meta_entity WHERE name = ? AND plugin_id = ?",
                String.class, entityName, pluginId);
        if (actualId == null) {
            throw new org.springframework.dao.DuplicateKeyException(
                    "实体名已被其他归属占用: " + entityName);
        }
        jdbc.update("DELETE FROM meta_field WHERE entity_id = ?", actualId);
        int position = 0;
        for (FieldSpec field : fields) {
            jdbc.update("INSERT INTO meta_field (id, entity_id, name, display_name, field_type,"
                            + " required, default_value, validation, position)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)",
                    "meta-" + UUID.randomUUID(), actualId, field.name(), field.displayName(),
                    field.fieldType(), field.required(),
                    orEmpty(field.defaultValueJson()), orEmpty(field.validationJson()), position++);
        }
        return actualId;
    }

    /** 平台侧（元数据管理）创建的实体 plugin_id 为 NULL 的归属哨兵（非合法插件 ID 字符集）。 */
    private static final String PLATFORM_OWNER = "<platform>";

    @Override
    public Optional<String> entityOwnerOf(String entityName) {
        return jdbc.query("SELECT plugin_id FROM meta_entity WHERE name = ?",
                (rs, n) -> rs.getString(1) == null ? PLATFORM_OWNER : rs.getString(1),
                entityName).stream().findFirst();
    }

    @Override
    public void upsertViewForEntity(String entityName, String viewType, String viewName,
                                    String columnsJson, String filtersJson) {
        jdbc.update("INSERT INTO meta_view (id, entity_id, view_type, name, columns, filters)"
                + " SELECT ?, e.id, ?, ?, ?::jsonb, ?::jsonb FROM meta_entity e"
                + " WHERE e.name = ?"
                + " ON CONFLICT (entity_id, view_type) DO UPDATE SET name = EXCLUDED.name,"
                + " columns = EXCLUDED.columns, filters = EXCLUDED.filters,"
                + " updated_at = now()",
                "mv-" + UUID.randomUUID(), viewType, viewName,
                orEmpty(columnsJson), orEmpty(filtersJson), entityName);
    }

    @Override
    public int deactivateEntity(String pluginId) {
        return jdbc.update("UPDATE meta_entity SET status = 'disabled', updated_at = now()"
                + " WHERE plugin_id = ?", pluginId);
    }

    @Override
    public void appendPluginEvent(String pluginId, String activationId, String eventType) {
        jdbc.update("INSERT INTO plugin_audit_event (id, plugin_id, activation_id, event_type)"
                + " VALUES (?, ?, ?, ?)", "pae-" + UUID.randomUUID(), pluginId, activationId,
                eventType);
    }

    private String activationSelect() {
        return "SELECT id, plugin_id, plugin_version_id, operation, status, stage, error_code,"
                + " requested_by, started_at, finished_at FROM plugin_activation";
    }

    private static String orEmpty(String json) {
        return json == null ? "{}" : json;
    }

    private ActivationRecord mapActivation(ResultSet rs, int rowNum) throws SQLException {
        return new ActivationRecord(rs.getString("id"), rs.getString("plugin_id"),
                rs.getString("plugin_version_id"), rs.getString("operation"),
                ActivationStatus.fromName(rs.getString("status")), rs.getString("stage"),
                rs.getString("error_code"), rs.getString("requested_by"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at") == null ? null
                        : rs.getTimestamp("finished_at").toInstant());
    }
}
