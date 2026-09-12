package com.flexforge.kb.domain;

import com.flexforge.common.PublicApi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 关键词检索（FR-KB-02，docs/09 P28 红线：MVP 不引入向量检索/embedding 依赖）：
 * 问题分词（拉丁词原样小写、CJK 连续段展开二元组）→ 条目评分（标题命中 ×2、
 * 正文 ×1）→ 分数降序取 Top-K。纯函数无状态，评分口径可单测复现。
 */
@PublicApi
public final class KbRetrieval {

    /** 单次注入条目上限（FR-KB-02）。 */
    public static final int TOP_K = 5;
    /** 检索词数量上限（超长问题截断评分词表，防评分面放大）。 */
    private static final int MAX_TERMS = 32;
    private static final int TITLE_WEIGHT = 2;
    private static final int CONTENT_WEIGHT = 1;

    private KbRetrieval() {
    }

    /** 命中条目（score &gt; 0，分降序、同分按标题稳定序），未命中返回空表。 */
    public static List<KbEntryRepository.KbEntryRecord> topMatches(
            String question, List<KbEntryRepository.KbEntryRecord> entries) {
        Set<String> terms = termsOf(question);
        if (terms.isEmpty()) {
            return List.of();
        }
        record Scored(KbEntryRepository.KbEntryRecord entry, int score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (KbEntryRepository.KbEntryRecord entry : entries) {
            int score = score(entry, terms);
            if (score > 0) {
                scored.add(new Scored(entry, score));
            }
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparing(s -> s.entry().title()));
        return scored.stream().limit(TOP_K).map(Scored::entry).toList();
    }

    /** 问题 → 检索词集：非字母数字切分；含 CJK 的段展开二元组（单字段保留单字）。 */
    static Set<String> termsOf(String question) {
        Set<String> terms = new LinkedHashSet<>();
        if (question == null) {
            return terms;
        }
        for (String raw : question.split("[^\\p{L}\\p{N}]+")) {
            if (raw.isEmpty()) {
                continue;
            }
            String token = raw.toLowerCase(Locale.ROOT);
            if (containsCjk(token)) {
                addCjkTerms(terms, token);
            } else if (token.length() >= 2) {
                terms.add(token);
            }
            if (terms.size() >= MAX_TERMS) {
                break;
            }
        }
        return Set.copyOf(terms);
    }

    /** CJK 段展开为二元组（长度 1 保留单字）：与条目侧同口径计数，中文问答可命中。 */
    private static void addCjkTerms(Set<String> terms, String token) {
        if (token.length() == 1) {
            terms.add(token);
            return;
        }
        for (int i = 0; i + 1 < token.length(); i++) {
            terms.add(token.substring(i, i + 2));
        }
    }

    private static boolean containsCjk(String token) {
        return token.codePoints().anyMatch(cp ->
                cp >= 0x4E00 && cp <= 0x9FFF || cp >= 0x3400 && cp <= 0x4DBF);
    }

    private static int score(KbEntryRepository.KbEntryRecord entry, Set<String> terms) {
        int score = 0;
        String title = entry.title() == null ? "" : entry.title().toLowerCase(Locale.ROOT);
        String content = entry.content() == null ? "" : entry.content().toLowerCase(Locale.ROOT);
        for (String term : terms) {
            score += TITLE_WEIGHT * count(title, term);
            score += CONTENT_WEIGHT * count(content, term);
        }
        return score;
    }

    private static int count(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
