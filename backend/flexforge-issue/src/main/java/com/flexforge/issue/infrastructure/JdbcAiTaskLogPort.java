package com.flexforge.issue.infrastructure;

import com.flexforge.issue.domain.AiTaskLogPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** ai_task_log JDBC 实现（V011）。 */
@Repository
public class JdbcAiTaskLogPort implements AiTaskLogPort {

    private final JdbcTemplate jdbc;

    public JdbcAiTaskLogPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int countOf(String issueId, String kind) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ai_task_log WHERE issue_id = ? AND kind = ?",
                Integer.class, issueId, kind);
        return count == null ? 0 : count;
    }

    @Override
    public void insert(TaskLogEntry entry) {
        jdbc.update("INSERT INTO ai_task_log (id, issue_id, kind, model, prompt_version,"
                        + " clarify_rounds, retries, output_valid, error_code, duration_ms)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "atl-" + UUID.randomUUID(), entry.issueId(), entry.kind(), entry.model(),
                entry.promptVersion(), entry.clarifyRounds(), entry.retries(),
                entry.outputValid(), entry.errorCode(), entry.durationMs());
    }
}
