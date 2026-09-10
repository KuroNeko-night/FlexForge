package com.flexforge.plugin.infrastructure;

import com.flexforge.plugin.domain.ProcessorArtifactRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** processor_artifact JDBC 实现（V016，P23）。 */
@Repository
public class JdbcProcessorArtifactRepository implements ProcessorArtifactRepository {

    private static final String COLUMNS = "id, processor_key, filename, content_type, size_bytes,"
            + " storage_dir, created_by, created_at, expires_at, downloaded_at";

    private final JdbcTemplate jdbc;

    public JdbcProcessorArtifactRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(ArtifactRecord record) {
        jdbc.update("INSERT INTO processor_artifact (" + COLUMNS + ")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                record.id(), record.processorKey(), record.filename(), record.contentType(),
                record.sizeBytes(), record.storageDir(), record.createdBy(),
                Timestamp.from(record.createdAt()), Timestamp.from(record.expiresAt()),
                record.downloadedAt() == null ? null : Timestamp.from(record.downloadedAt()));
    }

    @Override
    public ArtifactRecord find(String artifactId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM processor_artifact WHERE id = ?",
                row, artifactId).stream().findFirst().orElse(null);
    }

    @Override
    public void markDownloaded(String artifactId, Instant at) {
        jdbc.update("UPDATE processor_artifact SET downloaded_at = ?"
                + " WHERE id = ? AND downloaded_at IS NULL", Timestamp.from(at), artifactId);
    }

    @Override
    public List<ArtifactRecord> expiredBefore(Instant now) {
        return jdbc.query("SELECT " + COLUMNS + " FROM processor_artifact"
                + " WHERE expires_at < ?", row, Timestamp.from(now));
    }

    @Override
    public void delete(String artifactId) {
        jdbc.update("DELETE FROM processor_artifact WHERE id = ?", artifactId);
    }

    private final RowMapper<ArtifactRecord> row = (rs, n) -> new ArtifactRecord(
            rs.getString("id"), rs.getString("processor_key"), rs.getString("filename"),
            rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("storage_dir"),
            rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant(), nullable(rs, "downloaded_at"));

    private static Instant nullable(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
