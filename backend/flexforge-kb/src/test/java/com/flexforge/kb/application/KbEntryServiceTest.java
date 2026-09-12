package com.flexforge.kb.application;

import com.flexforge.kb.domain.FakeKbRepositories;
import com.flexforge.kb.domain.KbEntryRepository;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 条目服务单测（FR-KB-01/04）：尺寸校验 400 口径（IAE）、分类归一、
 * 404 口径（NoSuchElement）、增删改审计同口径。
 */
class KbEntryServiceTest {

    private final FakeKbRepositories.EntryStore store = new FakeKbRepositories.EntryStore();
    private final FakeKbRepositories.AuditSink audit = new FakeKbRepositories.AuditSink();
    private final KbEntryService service = new KbEntryService(
            store, audit, FakeKbRepositories.FIXED_CLOCK);

    @Test
    void createNormalizesBlankCategoryToNullAndAudits() {
        KbEntryRepository.KbEntryRecord saved =
                service.create("admin", " 报销规范 ", "  ", " 内容 ");
        assertThat(saved.title()).isEqualTo("报销规范");
        assertThat(saved.category()).isNull();
        assertThat(saved.content()).isEqualTo("内容");
        assertThat(audit.events).singleElement()
                .satisfies(e -> {
                    assertThat(e.action()).isEqualTo("kb.entry.create");
                    assertThat(e.objectId()).isEqualTo(saved.id());
                });
    }

    @Test
    void rejectsOversizeTitleAndContentAndBlank() {
        assertThatThrownBy(() -> service.create("admin", "标".repeat(121), null, "内容"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("120");
        assertThatThrownBy(() -> service.create("admin", "标题", null, "内".repeat(20_001)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("20000");
        assertThatThrownBy(() -> service.create("admin", " ", null, "内容"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("标题");
        assertThatThrownBy(() -> service.create("admin", "标题", "分类".repeat(21), "内容"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("40");
        assertThat(store.rows).isEmpty();
    }

    @Test
    void updateMissingEntryIs404Semantics() {
        assertThatThrownBy(() -> service.update("admin", "kb-none", "t", null, "c"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void deleteRemovesAndAudits() {
        KbEntryRepository.KbEntryRecord saved = service.create("admin", "t", null, "c");
        service.delete("admin", saved.id());
        assertThat(store.rows).isEmpty();
        assertThat(audit.events).extracting(e -> e.action())
                .containsExactly("kb.entry.create", "kb.entry.delete");
    }
}
