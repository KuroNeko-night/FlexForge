package com.flexforge.plugin.infrastructure;

import com.flexforge.plugin.domain.PresetRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/** plugin_preset JDBC 实现（P21）：payload 为条目数组 JSONB，读写同构。 */
@Repository
public class JdbcPresetRepository implements PresetRepository {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;

    public JdbcPresetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<PresetRecord> listAll() {
        return jdbc.query("SELECT id, name, payload, created_by, created_at FROM plugin_preset"
                        + " ORDER BY created_at DESC",
                (rs, n) -> new PresetRecord(rs.getString(1), rs.getString(2),
                        itemsOf(rs.getString(3)), rs.getString(4),
                        rs.getTimestamp(5).toInstant()));
    }

    @Override
    public Optional<PresetRecord> find(String id) {
        return jdbc.query("SELECT id, name, payload, created_by, created_at FROM plugin_preset"
                        + " WHERE id = ?",
                (rs, n) -> new PresetRecord(rs.getString(1), rs.getString(2),
                        itemsOf(rs.getString(3)), rs.getString(4),
                        rs.getTimestamp(5).toInstant()), id).stream().findFirst();
    }

    @Override
    public boolean nameExists(String name) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM plugin_preset WHERE name = ?", Integer.class, name);
        return count != null && count > 0;
    }

    @Override
    public void insert(PresetRecord preset) {
        try {
            jdbc.update("INSERT INTO plugin_preset (id, name, payload, created_by, created_at)"
                            + " VALUES (?, ?, ?::jsonb, ?, ?)",
                    preset.id(), preset.name(), itemsJson(preset.entries()),
                    preset.createdBy(), Timestamp.from(preset.createdAt()));
        } catch (DuplicateKeyException e) {
            // DB 唯一约束兜住应用层检查后的并发窗口：统一翻译为业务口径
            throw new IllegalArgumentException("同名预设已存在: " + preset.name());
        }
    }

    @Override
    public void delete(String id) {
        jdbc.update("DELETE FROM plugin_preset WHERE id = ?", id);
    }

    private static String itemsJson(List<PresetItem> entries) {
        List<java.util.Map<String, String>> rows = new java.util.ArrayList<>();
        for (PresetItem item : entries) {
            rows.add(java.util.Map.of("pluginId", item.pluginId(), "versionId", item.versionId(),
                    "version", item.version()));
        }
        return JSON.writeValueAsString(rows);
    }

    private static List<PresetItem> itemsOf(String payload) {
        JsonNode array = JSON.readTree(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!array.isArray()) {
            return List.of();
        }
        java.util.List<PresetItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JsonNode node = array.get(i);
            items.add(new PresetItem(node.path("pluginId").asString(),
                    node.path("versionId").asString(), node.path("version").asString()));
        }
        return List.copyOf(items);
    }
}
