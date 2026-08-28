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
    public Optional<PluginVersionRecord> findByContentHash(String contentHash) {
        return jdbc.query("SELECT id, plugin_id, version, content_hash, capability_level,"
                        + " manifest_json, script_checksums, size_bytes, created_at"
                        + " FROM plugin_version WHERE content_hash = ?", row, contentHash)
                .stream().findFirst();
    }

    @Override
    public Optional<PluginVersionRecord> findVersion(String pluginId, String version) {
        return jdbc.query("SELECT id, plugin_id, version, content_hash, capability_level,"
                        + " manifest_json, script_checksums, size_bytes, created_at"
                        + " FROM plugin_version WHERE plugin_id = ? AND version = ?", row, pluginId, version)
                .stream().findFirst();
    }

    @Override
    public List<String> versionsOf(String pluginId) {
        return jdbc.queryForList(
                "SELECT version FROM plugin_version WHERE plugin_id = ? ORDER BY created_at",
                String.class, pluginId);
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
                            + " capability_level, manifest_json, script_checksums, size_bytes)"
                            + " VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)",
                    version.id(), version.pluginId(), version.version(), version.contentHash(),
                    version.capabilityLevel(), version.manifestJson(),
                    checksumsJson(version.scriptChecksums()), version.sizeBytes());
        } catch (DuplicateKeyException e) {
            // 并发同包导入：约束兜底，调用方按幂等语义复查 content hash
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

    private PluginVersionRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, String> checksums = new HashMap<>();
        JsonNode node = JSON.readTree(rs.getString("script_checksums"));
        for (var entry : node.properties()) {
            checksums.put(entry.getKey(), entry.getValue().asText());
        }
        return new PluginVersionRecord(rs.getString("id"), rs.getString("plugin_id"),
                rs.getString("version"), rs.getString("content_hash"),
                rs.getInt("capability_level"), rs.getString("manifest_json"), checksums,
                rs.getLong("size_bytes"), rs.getTimestamp("created_at").toInstant());
    }
}
