package com.flexforge.plugin.infrastructure;

import com.flexforge.plugin.domain.DependencySpec;
import com.flexforge.plugin.domain.PluginPackageRepository;
import com.flexforge.plugin.domain.PluginVersionRecord;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** plugin_* 版本存储 JDBC 实现（V006；storeVersion 事务化三表写入）。 */
@Repository
public class JdbcPluginRepository implements PluginPackageRepository {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RowMapper<PluginVersionRecord> row = this::mapRow;

    private final JdbcTemplate jdbc;

    public JdbcPluginRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PluginVersionRecord> findByVersionId(String versionId) {
        return jdbc.query(versionSelect() + " WHERE id = ?", row, versionId).stream().findFirst();
    }

    @Override
    public Optional<PluginVersionRecord> findByContentHash(String contentHash) {
        return jdbc.query(versionSelect() + " WHERE content_hash = ?", row, contentHash)
                .stream().findFirst();
    }

    @Override
    public Optional<PluginVersionRecord> findVersion(String pluginId, String version) {
        return jdbc.query(versionSelect()
                        + " WHERE plugin_id = ? AND version = ?", row, pluginId, version)
                .stream().findFirst();
    }

    @Override
    public List<String> versionsOf(String pluginId) {
        return jdbc.queryForList(
                "SELECT version FROM plugin_version WHERE plugin_id = ? ORDER BY created_at",
                String.class, pluginId);
    }

    @Override
    public List<PluginPackageRepository.InstanceEntry> listInstances() {
        return jdbc.query("SELECT plugin_id, name, status FROM plugin_instance ORDER BY plugin_id",
                (rs, n) -> new PluginPackageRepository.InstanceEntry(rs.getString(1),
                        rs.getString(2), rs.getString(3)));
    }

    @Override
    public List<PluginPackageRepository.VersionEntry> versionSummariesOf(String pluginId) {
        return jdbc.query("SELECT id, version, created_at FROM plugin_version"
                        + " WHERE plugin_id = ? ORDER BY created_at",
                (rs, n) -> new PluginPackageRepository.VersionEntry(rs.getString(1),
                        rs.getString(2), rs.getTimestamp(3).toInstant()), pluginId);
    }

    @Override
    @Transactional
    public PluginVersionRecord storeVersion(String pluginName, PluginVersionRecord version,
                                            List<DependencySpec> dependencies) {
        jdbc.update("INSERT INTO plugin_instance (id, plugin_id, name)"
                        + " VALUES (?, ?, ?)"
                        + " ON CONFLICT (plugin_id) DO UPDATE SET name = EXCLUDED.name, updated_at = now()",
                "pi-" + UUID.randomUUID(), version.pluginId(), pluginName);
        try {
            jdbc.update("INSERT INTO plugin_version (id, plugin_id, version, content_hash,"
                            + " capability_level, manifest_json, script_checksums, size_bytes,"
                            + " script_payloads, resource_payloads, asset_payloads)"
                            + " VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?::jsonb,"
                            + " ?::jsonb)",
                    version.id(), version.pluginId(), version.version(), version.contentHash(),
                    version.capabilityLevel(), version.manifestJson(),
                    checksumsJson(version.scriptChecksums()), version.sizeBytes(),
                    payloadsJson(version.scriptPayloads()),
                    payloadsJson(version.resourcePayloads()),
                    payloadsJson(version.assetPayloads()));
        } catch (DuplicateKeyException e) {
            throw e;
        }
        for (DependencySpec dependency : dependencies) {
            jdbc.update("INSERT INTO plugin_dependency (id, plugin_version_id, dependency_id, version_range)"
                            + " VALUES (?, ?, ?, ?) ON CONFLICT (plugin_version_id, dependency_id) DO NOTHING",
                    "pd-" + UUID.randomUUID(), version.id(), dependency.pluginId(),
                    dependency.versionRange());
        }
        return findVersion(version.pluginId(), version.version()).orElse(version);
    }

    private static String checksumsJson(Map<String, String> checksums) {
        return JSON.writeValueAsString(checksums == null ? Map.of() : checksums);
    }

    private static String payloadsJson(Map<String, String> payloads) {
        return JSON.writeValueAsString(payloads == null ? Map.of() : payloads);
    }

    private String versionSelect() {
        return "SELECT id, plugin_id, version, content_hash, capability_level,"
                + " manifest_json, script_checksums, size_bytes, created_at,"
                + " script_payloads, resource_payloads, asset_payloads FROM plugin_version";
    }

    private PluginVersionRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, String> checksums = stringMapOf(rs.getString("script_checksums"));
        Map<String, String> scripts = stringMapOf(rs.getString("script_payloads"));
        Map<String, String> resources = stringMapOf(rs.getString("resource_payloads"));
        Map<String, String> assets = stringMapOf(rs.getString("asset_payloads"));
        return new PluginVersionRecord(rs.getString("id"), rs.getString("plugin_id"),
                rs.getString("version"), rs.getString("content_hash"),
                rs.getInt("capability_level"), rs.getString("manifest_json"), checksums,
                rs.getLong("size_bytes"), rs.getTimestamp("created_at").toInstant(),
                scripts, resources, assets);
    }

    private static Map<String, String> stringMapOf(String json) {
        Map<String, String> result = new HashMap<>();
        if (json == null) {
            return result;
        }
        JsonNode node = JSON.readTree(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (var entry : node.properties()) {
            result.put(entry.getKey(), entry.getValue().asText());
        }
        return result;
    }
}
