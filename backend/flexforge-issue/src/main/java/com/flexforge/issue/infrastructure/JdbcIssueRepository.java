package com.flexforge.issue.infrastructure;

import com.flexforge.issue.domain.IssueRepository;
import com.flexforge.issue.domain.IssueStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/** issue 域五表 JDBC 实现（V010）；applyTransition 事务内更新状态+写迁移记录。 */
@Repository
public class JdbcIssueRepository implements IssueRepository {

    private final JdbcTemplate jdbc;

    public JdbcIssueRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public IssueRecord insertIssue(String id, String title, String description,
                                   String createdBy, List<String> labels) {
        jdbc.update("INSERT INTO issue (id, title, description, status, created_by)"
                        + " VALUES (?, ?, ?, 'SUBMITTED', ?)",
                id, title, description, createdBy);
        insertLabels(id, labels);
        return findIssue(id);
    }

    @Override
    public IssueRecord findIssue(String issueId) {
        return jdbc.query("SELECT id, title, description, status, created_by, assigned_to,"
                        + " created_at, updated_at, published_at FROM issue WHERE id = ?",
                issueRow, issueId).stream().findFirst().orElse(null);
    }

    /** 乐观锁 CAS：UPDATE 带 {@code status = from} 条件，并发双迁时只有一方生效，
     * 败者更新 0 行并得到可诊断冲突（而非静默覆盖）；状态更新与 issue_transition
     * 行在同一事务，避免出现无记录或记录指向未发生状态的状态。 */
    @Override
    @Transactional
    public IssueRecord applyTransition(String issueId, IssueStatus from, IssueStatus to,
                                       String operator, String reason) {
        int updated = jdbc.update("UPDATE issue SET status = ?, updated_at = now()"
                        + " WHERE id = ? AND status = ?", to.name(), issueId, from.name());
        if (updated == 0) {
            throw new com.flexforge.issue.domain.InvalidTransitionException(
                    "状态已变化（并发迁移），请刷新后重试: " + issueId);
        }
        jdbc.update("INSERT INTO issue_transition (id, issue_id, from_status, to_status,"
                        + " operator, reason) VALUES (?, ?, ?, ?, ?, ?)",
                "it-" + UUID.randomUUID(), issueId, from.name(), to.name(), operator, reason);
        return findIssue(issueId);
    }

    @Override
    public List<IssueRecord> listIssues(IssueStatus status, int offset, int limit) {
        if (status == null) {
            return jdbc.query("SELECT id, title, description, status, created_by, assigned_to,"
                    + " created_at, updated_at, published_at FROM issue ORDER BY created_at DESC"
                    + " OFFSET ? LIMIT ?", issueRow, offset, limit);
        }
        return jdbc.query("SELECT id, title, description, status, created_by, assigned_to,"
                        + " created_at, updated_at, published_at FROM issue WHERE status = ?"
                        + " ORDER BY created_at DESC OFFSET ? LIMIT ?",
                issueRow, status.name(), offset, limit);
    }

    @Override
    public List<IssueRecord> listIssuesByCreator(String creator, int offset, int limit) {
        return jdbc.query("SELECT id, title, description, status, created_by, assigned_to,"
                        + " created_at, updated_at, published_at FROM issue"
                        + " WHERE created_by = ? ORDER BY created_at DESC OFFSET ? LIMIT ?",
                issueRow, creator, offset, limit);
    }

    @Override
    @Transactional
    public void markPublished(String issueId) {
        jdbc.update("UPDATE issue SET published_at = now(), updated_at = now()"
                + " WHERE id = ? AND published_at IS NULL", issueId);
    }

    @Override
    @Transactional
    public void replaceLabels(String issueId, List<String> labels) {
        jdbc.update("DELETE FROM issue_label WHERE issue_id = ?", issueId);
        insertLabels(issueId, labels);
        jdbc.update("UPDATE issue SET updated_at = now() WHERE id = ?", issueId);
    }

    @Override
    public void updateAssignee(String issueId, String assignee) {
        jdbc.update("UPDATE issue SET assigned_to = ?, updated_at = now() WHERE id = ?",
                assignee, issueId);
    }

    @Override
    public void insertComment(String issueId, String author, String body) {
        jdbc.update("INSERT INTO issue_comment (id, issue_id, author, body)"
                        + " VALUES (?, ?, ?, ?)",
                "ic-" + UUID.randomUUID(), issueId, author, body);
    }

    @Override
    public List<IssueCommentRecord> commentsOf(String issueId) {
        return jdbc.query("SELECT id, issue_id, author, body, created_at FROM issue_comment"
                        + " WHERE issue_id = ? ORDER BY created_at",
                commentRow, issueId);
    }

    @Override
    public List<IssueTransitionRecord> transitionsOf(String issueId) {
        return jdbc.query("SELECT id, issue_id, from_status, to_status, operator, reason,"
                        + " created_at FROM issue_transition WHERE issue_id = ?"
                        + " ORDER BY created_at", transitionRow, issueId);
    }

    /** 原子取号 + 唯一约束冲突重试：并发保存规格败者换号重插（最多 3 次）。
     * 刻意不加方法级事务：Spring 事务内捕获 DuplicateKeyException（RuntimeException
     * 子类）会把事务标记 rollback-only，循环重试在单事务内必然整体回滚；无事务时
     * 每次尝试独立提交，重试语句才能取到包含胜者新号的新快照。 */
    @Override
    public SpecRevisionRecord insertSpec(String issueId, SpecContent content, String createdBy) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                jdbc.update("INSERT INTO requirement_spec (id, issue_id, schema_version,"
                                + " revision, spec_json, valid, validation_errors, brief_json,"
                                + " created_by)"
                                + " SELECT ?, ?, ?, coalesce(max(revision), 0) + 1, ?::jsonb, ?,"
                                + " ?::jsonb, ?::jsonb, ? FROM requirement_spec WHERE issue_id = ?",
                        "rs-" + UUID.randomUUID(), issueId, content.schemaVersion(),
                        content.specJson(), content.valid(), content.errorsJson(),
                        content.briefJson(), createdBy, issueId);
                return latestSpec(issueId);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发窗口：另一保存已占用该 revision，换号重试
            }
        }
        throw new IllegalArgumentException("并发保存规格冲突，请重试: " + issueId);
    }

    @Override
    public SpecRevisionRecord latestSpec(String issueId) {
        return jdbc.query("SELECT id, issue_id, schema_version, revision, spec_json::text,"
                        + " valid, validation_errors::text, brief_json::text, created_by,"
                        + " created_at FROM requirement_spec WHERE issue_id = ?"
                        + " ORDER BY revision DESC LIMIT 1", specRow, issueId)
                .stream().findFirst().orElse(null);
    }

    @Override
    public List<SpecRevisionRecord> specRevisionsOf(String issueId) {
        return jdbc.query("SELECT id, issue_id, schema_version, revision, spec_json::text,"
                        + " valid, validation_errors::text, brief_json::text, created_by,"
                        + " created_at FROM requirement_spec WHERE issue_id = ?"
                        + " ORDER BY revision DESC", specRow, issueId);
    }

    private void insertLabels(String issueId, List<String> labels) {
        for (String label : labels) {
            jdbc.update("INSERT INTO issue_label (issue_id, label) VALUES (?, ?)"
                    + " ON CONFLICT DO NOTHING", issueId, label);
        }
    }

    private final RowMapper<IssueRecord> issueRow = this::mapIssueRow;

    private IssueRecord mapIssueRow(ResultSet rs, int rowNum) throws SQLException {
        String id = rs.getString("id");
        List<String> labels = jdbc.queryForList(
                "SELECT label FROM issue_label WHERE issue_id = ? ORDER BY label",
                String.class, id);
        java.sql.Timestamp published = rs.getTimestamp("published_at");
        return new IssueRecord(id, rs.getString("title"), rs.getString("description"),
                IssueStatus.fromName(rs.getString("status")), rs.getString("created_by"),
                rs.getString("assigned_to"), List.copyOf(labels),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                published == null ? null : published.toInstant());
    }

    private final RowMapper<IssueCommentRecord> commentRow = (rs, n) -> new IssueCommentRecord(
            rs.getString("id"), rs.getString("issue_id"), rs.getString("author"),
            rs.getString("body"), rs.getTimestamp("created_at").toInstant());

    private final RowMapper<IssueTransitionRecord> transitionRow =
            (rs, n) -> new IssueTransitionRecord(rs.getString("id"), rs.getString("issue_id"),
                    IssueStatus.fromName(rs.getString("from_status")),
                    IssueStatus.fromName(rs.getString("to_status")),
                    rs.getString("operator"), rs.getString("reason"),
                    rs.getTimestamp("created_at").toInstant());

    private final RowMapper<SpecRevisionRecord> specRow = (rs, n) -> new SpecRevisionRecord(
            rs.getString("id"), rs.getString("issue_id"), rs.getInt("schema_version"),
            rs.getInt("revision"), rs.getString("spec_json"), rs.getBoolean("valid"),
            rs.getString("validation_errors"), rs.getString("brief_json"),
            rs.getString("created_by"), rs.getTimestamp("created_at").toInstant());
}
