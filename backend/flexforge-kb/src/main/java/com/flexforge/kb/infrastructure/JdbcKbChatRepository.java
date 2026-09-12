package com.flexforge.kb.infrastructure;

import com.flexforge.kb.domain.KbChatRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** kb_chat_message JDBC 实现（V018）：用户与助手消息同事务成对落库。 */
@Repository
public class JdbcKbChatRepository implements KbChatRepository {

    private static final RowMapper<KbMessageRecord> ROW = (rs, i) -> new KbMessageRecord(
            rs.getString("id"), rs.getString("user_id"), rs.getString("role"),
            rs.getString("content"), rs.getString("references_json"));

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
    public void insertExchange(KbMessageRecord userMessage, KbMessageRecord assistantMessage) {
        jdbc.update("INSERT INTO kb_chat_message (id, user_id, role, content, references_json)"
                        + " VALUES (?, ?, 'user', ?, NULL)",
                userMessage.id(), userMessage.userId(), userMessage.content());
        jdbc.update("INSERT INTO kb_chat_message (id, user_id, role, content, references_json)"
                        + " VALUES (?, ?, 'assistant', ?, ?)",
                assistantMessage.id(), assistantMessage.userId(), assistantMessage.content(),
                assistantMessage.referencesJson());
    }

    @Override
    public int deleteAllOf(String userId) {
        return jdbc.update("DELETE FROM kb_chat_message WHERE user_id = ?", userId);
    }
}
