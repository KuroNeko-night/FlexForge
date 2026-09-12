package com.flexforge.kb.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.flexforge.common.audit.AuditEvent;
import com.flexforge.common.audit.AuditEventPort;

/** 服务层单测的内存桩：条目/会话仓储与审计端口（确定性、可检视）。 */
public final class FakeKbRepositories {

    public static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-12T08:00:00Z"), ZoneOffset.UTC);

    public static class EntryStore implements KbEntryRepository {
        public final Map<String, KbEntryRecord> rows = new LinkedHashMap<>();

        @Override
        public List<KbEntryRecord> listAll() {
            return List.copyOf(rows.values());
        }

        @Override
        public KbEntryRecord find(String id) {
            return rows.get(id);
        }

        @Override
        public KbEntryRecord insert(KbEntryRecord entry) {
            rows.put(entry.id(), entry);
            return entry;
        }

        @Override
        public KbEntryRecord update(KbEntryRecord entry) {
            rows.put(entry.id(), entry);
            return entry;
        }

        @Override
        public void delete(String id) {
            rows.remove(id);
        }
    }

    public static class ChatStore implements KbChatRepository {
        public final List<KbMessageRecord> rows = new ArrayList<>();

        @Override
        public List<KbMessageRecord> recentOf(String userId, int limit) {
            return rows.stream().filter(m -> m.userId().equals(userId))
                    .skip(Math.max(0, countOf(userId) - limit)).toList();
        }

        @Override
        public void insertExchange(KbMessageRecord userMessage, KbMessageRecord assistantMessage) {
            rows.add(userMessage);
            rows.add(assistantMessage);
        }

        @Override
        public int deleteAllOf(String userId) {
            int before = rows.size();
            rows.removeIf(m -> m.userId().equals(userId));
            return before - rows.size();
        }

        int countOf(String userId) {
            return (int) rows.stream().filter(m -> m.userId().equals(userId)).count();
        }
    }

    public static class AuditSink implements AuditEventPort {
        public final List<AuditEvent> events = new ArrayList<>();

        @Override
        public void record(AuditEvent event) {
            events.add(event);
        }
    }

    private FakeKbRepositories() {
    }
}
