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
        public final List<KbAttachmentRecord> attachments = new ArrayList<>();

        @Override
        public List<KbMessageRecord> recentOf(String userId, int limit) {
            return rows.stream().filter(m -> m.userId().equals(userId))
                    .skip(Math.max(0, countOf(userId) - limit)).toList();
        }

        @Override
        public void insertExchange(KbMessageRecord userMessage, KbMessageRecord assistantMessage,
                                   List<KbAttachmentRecord> attachmentRows) {
            rows.add(userMessage);
            rows.add(assistantMessage);
            attachments.addAll(attachmentRows);
        }

        @Override
        public List<KbAttachmentView> attachmentsOf(List<String> messageIds) {
            return attachments.stream()
                    .filter(a -> messageIds.contains(a.messageId()))
                    .map(a -> new KbAttachmentView(a.id(), a.messageId(), a.filename(),
                            a.contentType(), a.sizeBytes()))
                    .toList();
        }

        @Override
        public OwnedAttachment findOwned(String attachmentId) {
            return attachments.stream()
                    .filter(a -> a.id().equals(attachmentId))
                    .findFirst()
                    .map(a -> new OwnedAttachment(ownerOf(a.messageId()), a.filename(),
                            a.contentType(), a.data()))
                    .orElse(null);
        }

        private String ownerOf(String messageId) {
            return rows.stream().filter(m -> m.id().equals(messageId))
                    .findFirst().map(KbMessageRecord::userId).orElse("unknown");
        }

        @Override
        public int deleteAllOf(String userId) {
            int before = rows.size();
            List<String> removedIds = rows.stream().filter(m -> m.userId().equals(userId))
                    .map(KbMessageRecord::id).toList();
            attachments.removeIf(a -> removedIds.contains(a.messageId()));
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
