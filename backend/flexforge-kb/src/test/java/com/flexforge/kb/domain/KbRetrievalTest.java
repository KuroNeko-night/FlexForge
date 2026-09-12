package com.flexforge.kb.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 关键词检索单测（FR-KB-02）：分词口径（拉丁词/CJK 二元组）、标题 ×2 权重、
 * Top-K 截断、无命中空表。
 */
class KbRetrievalTest {

    private static KbEntryRepository.KbEntryRecord entry(String title, String content) {
        return new KbEntryRepository.KbEntryRecord("kb-x", title, null, content, "admin", null);
    }

    @Test
    void termsSplitLatinWordsAndSkipSingleLetters() {
        assertThat(KbRetrieval.termsOf("How to export CSV?"))
                .containsExactlyInAnyOrder("how", "to", "export", "csv");
        assertThat(KbRetrieval.termsOf("a b cd")).containsExactly("cd");
    }

    @Test
    void cjkRunsExpandToBigrams() {
        assertThat(KbRetrieval.termsOf("报销流程"))
                .containsExactlyInAnyOrder("报销", "销流", "流程");
        assertThat(KbRetrieval.termsOf("税")).containsExactly("税");
    }

    @Test
    void titleMatchOutweighsContentMatch() {
        List<KbEntryRepository.KbEntryRecord> entries = List.of(
                entry("差旅报销规范", "正文与问题无关"),
                entry("无关标题", "出差前需要先提交申请，报销流程见附件"));
        List<KbEntryRepository.KbEntryRecord> top =
                KbRetrieval.topMatches("报销", entries);
        assertThat(top).hasSize(2);
        assertThat(top.get(0).title()).isEqualTo("差旅报销规范");
    }

    @Test
    void topMatchesCapAtFiveAndDropZeroScore() {
        List<KbEntryRepository.KbEntryRecord> entries = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) {
            entries.add(entry("报销规范" + i, "内容含报销 " + i + " 次"));
        }
        entries.add(entry("完全无关", "零分条目"));
        List<KbEntryRepository.KbEntryRecord> top = KbRetrieval.topMatches("报销", entries);
        assertThat(top).hasSize(KbRetrieval.TOP_K);
        assertThat(top).allSatisfy(e -> assertThat(e.title()).contains("报销规范"));
    }

    @Test
    void blankQuestionMatchesNothing() {
        assertThat(KbRetrieval.topMatches("  !! ", List.of(entry("t", "c")))).isEmpty();
        assertThat(KbRetrieval.topMatches(null, List.of(entry("t", "c")))).isEmpty();
    }
}
