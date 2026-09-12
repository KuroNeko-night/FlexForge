package com.flexforge.kb.infrastructure;

import com.flexforge.kb.domain.KbEntryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** kb_entry JDBC 实现（V018）：updated_at 每次写入刷新，列表倒序上限 200。 */
@Repository
public class JdbcKbEntryRepository implements KbEntryRepository {

    private static final int LIST_CAP = 200;

    /** updated_at 文本呈现（ISO-8601），与既有接口时间戳口径一致。 */
    private static final RowMapper<KbEntryRecord> ROW = (rs, i) -> new KbEntryRecord(
            rs.getString("id"), rs.getString("title"), rs.getString("category"),
            rs.getString("content"), rs.getString("created_by"),
            rs.getTimestamp("updated_at").toInstant().toString());

    private final JdbcTemplate jdbc;

    public JdbcKbEntryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<KbEntryRecord> listAll() {
        return jdbc.query(
                "SELECT id, title, category, content, created_by, updated_at"
                        + " FROM kb_entry ORDER BY updated_at DESC LIMIT " + LIST_CAP, ROW);
    }

    @Override
    public KbEntryRecord find(String id) {
        List<KbEntryRecord> found = jdbc.query(
                "SELECT id, title, category, content, created_by, updated_at"
                        + " FROM kb_entry WHERE id = ?", ROW, id);
        return found.isEmpty() ? null : found.get(0);
    }

    @Override
    public KbEntryRecord insert(KbEntryRecord entry) {
        jdbc.update("INSERT INTO kb_entry (id, title, category, content, created_by)"
                        + " VALUES (?, ?, ?, ?, ?)",
                entry.id(), entry.title(), entry.category(), entry.content(),
                entry.createdBy());
        return find(entry.id());
    }

    @Override
    public KbEntryRecord update(KbEntryRecord entry) {
        jdbc.update("UPDATE kb_entry SET title = ?, category = ?, content = ?,"
                        + " updated_at = now() WHERE id = ?",
                entry.title(), entry.category(), entry.content(), entry.id());
        return find(entry.id());
    }

    @Override
    public void delete(String id) {
        jdbc.update("DELETE FROM kb_entry WHERE id = ?", id);
    }
}
