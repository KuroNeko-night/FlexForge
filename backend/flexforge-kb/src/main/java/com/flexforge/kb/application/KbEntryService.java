package com.flexforge.kb.application;

import com.flexforge.common.PublicApi;
import com.flexforge.common.audit.AuditEventPort;
import com.flexforge.common.audit.AuditEvents;
import com.flexforge.kb.domain.KbEntryRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * 知识库条目服务（FR-KB-01）：尺寸校验（超限 IAE→400）+ 仓储 + 审计。
 * 写操作仅 ADMIN（控制器 @RequireRole 收口，S2），本服务不重复判权。
 */
@PublicApi
@Service
public class KbEntryService {

    /** 尺寸硬上限（docs/13 §3.6-7，与 V018 CHECK 同口径）。 */
    public static final int TITLE_MAX = 120;
    public static final int CATEGORY_MAX = 40;
    public static final int CONTENT_MAX = 20_000;

    private final KbEntryRepository repository;
    private final AuditEventPort audit;
    private final Clock clock;

    public KbEntryService(KbEntryRepository repository, AuditEventPort audit, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    public List<KbEntryRepository.KbEntryRecord> list() {
        return repository.listAll();
    }

    public KbEntryRepository.KbEntryRecord create(String operator, String title,
                                                  String category, String content) {
        String normalized = validate(title, category, content);
        KbEntryRepository.KbEntryRecord saved = repository.insert(
                new KbEntryRepository.KbEntryRecord("kb-" + UUID.randomUUID(),
                        title.strip(), normalized, content.strip(), operator, null));
        audit.record(AuditEvents.of(operator, "kb.entry.create", saved.id(), "created", clock));
        return saved;
    }

    public KbEntryRepository.KbEntryRecord update(String operator, String id, String title,
                                                  String category, String content) {
        require(id);
        String normalized = validate(title, category, content);
        KbEntryRepository.KbEntryRecord saved = repository.update(
                new KbEntryRepository.KbEntryRecord(id, title.strip(), normalized,
                        content.strip(), operator, null));
        if (saved == null) {
            // 竞态窗口：校验存在后条目被并发删除（审查 P3-5）——按 404 口径而非空 200
            throw new NoSuchElementException("知识条目不存在: " + id);
        }
        audit.record(AuditEvents.of(operator, "kb.entry.update", id, "updated", clock));
        return saved;
    }

    public void delete(String operator, String id) {
        require(id);
        repository.delete(id);
        audit.record(AuditEvents.of(operator, "kb.entry.delete", id, "deleted", clock));
    }

    private void require(String id) {
        if (repository.find(id) == null) {
            throw new NoSuchElementException("知识条目不存在: " + id);
        }
    }

    /** 分类空白归一为 null；标题/正文尺寸与空白校验（IAE 消息面向用户，可直接回显）。
     * 标题与分类禁换行（审查 P3-2：换行可伪造知识段"### [x] 标题"条目行）。 */
    private String validate(String title, String category, String content) {
        requireText(title, "标题", TITLE_MAX);
        requireSingleLine(title, "标题");
        requireText(content, "正文", CONTENT_MAX);
        String normalized = category == null ? null : category.strip();
        if (normalized != null && normalized.isEmpty()) {
            normalized = null;
        }
        if (normalized != null) {
            requireSingleLine(normalized, "分类");
            if (normalized.length() > CATEGORY_MAX) {
                throw new IllegalArgumentException("分类超过 " + CATEGORY_MAX + " 字符上限");
            }
        }
        return normalized;
    }

    private static void requireText(String value, String label, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        if (value.strip().length() > max) {
            throw new IllegalArgumentException(label + "超过 " + max + " 字符上限");
        }
    }

    private static void requireSingleLine(String value, String label) {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(label + "不能包含换行");
        }
    }
}
