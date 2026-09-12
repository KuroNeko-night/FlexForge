package com.flexforge.kb.infrastructure;

import com.flexforge.kb.domain.KbChatRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.util.List;

/** kb_chat_message/kb_attachment JDBC 实现（V018/V019）：消息与附件同事务成对落库。 */
@Repository
public class JdbcKbChatRepository implements KbChatRepository {

    private static final RowMapper<KbMessageRecord> ROW = (rs, i) -> new KbMessageRecord(
            rs.getString("id"), rs.getString("user_id"), rs.getString("role"),
            rs.getString("content"), rs.getString("references_json"));

    private static final RowMapper<KbAttachmentView> ATTACHMENT_ROW =
            (rs, i) -> new KbAttachmentView(rs.getString("id"), rs.getString("message_id"),
                    rs.getString("filename"), rs.getString("content_type"),
                    rs.getLong("size_bytes"));

    private final JdbcTemplate jdbc;

    public JdbcKbChatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<KbMessageRecord> recentOf(String userId, int limit) {
        return jdbc.query("SELECT id, user_id, role, content, references_json FROM ("
                        + " SELECT id, user_id, role, content, references_json, seq"
                        + " FROM kb_chat_message WHERE user_id = ?"
                        + " ORDER BY seq DESC LIMIT ?) recent"
                        + " ORDER BY seq ASC",
                ROW, userId, limit);
    }

    @Override
    @Transactional
    public void insertExchange(KbMessageRecord userMessage, KbMessageRecord assistantMessage,
                               List<KbAttachmentRecord> attachments) {
        jdbc.update("INSERT INTO kb_chat_message (id, user_id, role, content, references_json)"
                        + " VALUES (?, ?, 'user', ?, NULL)",
                userMessage.id(), userMessage.userId(), userMessage.content());
        jdbc.update("INSERT INTO kb_chat_message (id, user_id, role, content, references_json)"
                        + " VALUES (?, ?, 'assistant', ?, ?)",
                assistantMessage.id(), assistantMessage.userId(), assistantMessage.content(),
                assistantMessage.referencesJson());
        jdbc.batchUpdate("INSERT INTO kb_attachment (id, message_id, filename, content_type,"
                        + " size_bytes, data, extracted_text) VALUES (?, ?, ?, ?, ?, ?, ?)",
                attachments, attachments.size(),
                (PreparedStatement ps, KbAttachmentRecord attachment) -> {
                    ps.setString(1, attachment.id());
                    ps.setString(2, attachment.messageId());
                    ps.setString(3, attachment.filename());
                    ps.setString(4, attachment.contentType());
                    ps.setLong(5, attachment.sizeBytes());
                    ps.setBytes(6, attachment.data());
                    ps.setString(7, attachment.extractedText());
                });
    }

    @Override
    public List<KbAttachmentView> attachmentsOf(List<String> messageIds) {
        if (messageIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(messageIds.size(), "?"));
        return jdbc.query("SELECT id, message_id, filename, content_type, size_bytes"
                        + " FROM kb_attachment WHERE message_id IN (" + placeholders + ")"
                        + " ORDER BY created_at ASC",
                ATTACHMENT_ROW, messageIds.toArray());
    }

    @Override
    public OwnedAttachment findOwned(String attachmentId) {
        List<OwnedAttachment> found = jdbc.query(
                "SELECT m.user_id AS owner_id, a.filename, a.content_type, a.data"
                        + " FROM kb_attachment a JOIN kb_chat_message m ON m.id = a.message_id"
                        + " WHERE a.id = ?",
                (rs, i) -> new OwnedAttachment(rs.getString("owner_id"), rs.getString("filename"),
                        rs.getString("content_type"), rs.getBytes("data")),
                attachmentId);
        return found.isEmpty() ? null : found.get(0);
    }

    @Override
    public int deleteAllOf(String userId) {
        return jdbc.update("DELETE FROM kb_chat_message WHERE user_id = ?", userId);
    }
}
