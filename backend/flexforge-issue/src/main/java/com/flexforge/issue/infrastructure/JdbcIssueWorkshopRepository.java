package com.flexforge.issue.infrastructure;

import com.flexforge.issue.domain.IssueWorkshopRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** issue_workshop_message JDBC 实现（V021）。 */
@Repository
public class JdbcIssueWorkshopRepository implements IssueWorkshopRepository {

    private static final RowMapper<WorkshopMessageRecord> ROW = (rs, i) -> new WorkshopMessageRecord(
            rs.getString("id"), rs.getString("user_id"), rs.getString("role"),
            rs.getString("content"), rs.getString("issue_id"));

    private final JdbcTemplate jdbc;

    public JdbcIssueWorkshopRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<WorkshopMessageRecord> recentOf(String userId, int limit) {
        return jdbc.query("SELECT id, user_id, role, content, issue_id FROM ("
                        + " SELECT id, user_id, role, content, issue_id, seq"
                        + " FROM issue_workshop_message WHERE user_id = ?"
                        + " ORDER BY seq DESC LIMIT ?) recent"
                        + " ORDER BY seq ASC",
                ROW, userId, limit);
    }

    @Override
    @Transactional
    public void insertExchange(WorkshopMessageRecord userMessage, WorkshopMessageRecord assistantMessage) {
        jdbc.update("INSERT INTO issue_workshop_message (id, user_id, role, content, issue_id)"
                        + " VALUES (?, ?, 'user', ?, NULL)",
                userMessage.id(), userMessage.userId(), userMessage.content());
        jdbc.update("INSERT INTO issue_workshop_message (id, user_id, role, content, issue_id)"
                        + " VALUES (?, ?, 'assistant', ?, ?)",
                assistantMessage.id(), assistantMessage.userId(), assistantMessage.content(),
                assistantMessage.issueId());
    }

    @Override
    public int deleteAllOf(String userId) {
        return jdbc.update("DELETE FROM issue_workshop_message WHERE user_id = ?", userId);
    }
}
