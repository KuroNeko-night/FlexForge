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
 * 正文 ×1）→ 分数降序取 Top-K。多词问题需至少两个不同检索词命中（共现佐证），
 * 防止"量子力学入门"因通用后缀"入门"误命中《平台使用入门》类假阳性。
 * 纯函数无状态，评分口径可单测复现。
 */
@PublicApi
public final class KbRetrieval {

    /** 单次注入条目上限（FR-KB-02）。 */
    public static final int TOP_K = 5;
    /** 多词问题的最小不同命中词数（共现佐证阈值）。 */
    static final int MIN_DISTINCT_TERMS_MULTI = 2;
    /** 检索词数量上限（超长问题截断评分词表，防评分面放大）。 */
    private static final int MAX_TERMS = 32;
    private static final int TITLE_WEIGHT = 2;
    private static final int CONTENT_WEIGHT = 1;

    private KbRetrieval() {
    }

    /** 命中条目（score &gt; 0 且过共现佐证，分降序、同分按标题稳定序），未命中返回空表。 */
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
            Match match = match(entry, terms);
            if (match.score() > 0 && match.distinctTerms() >= distinctFloor(terms.size())) {
                scored.add(new Scored(entry, match.score()));
            }
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparing(s -> s.entry().title()));
        return scored.stream().limit(TOP_K).map(Scored::entry).toList();
    }

    /** 共现下限：问题词数 ≥4 时需 ≥2 个不同命中词，短问题（1-3 词）单命中即可。 */
    static int distinctFloor(int termCount) {
        return termCount >= 4 ? MIN_DISTINCT_TERMS_MULTI : 1;
    }

    /** 问题 → 检索词集：非字母数字切分；含 CJK 的段展开二元组（单字段保留单字）。
     * 上限在二元组展开中逐项检查（审查 P3-3：原实现按整 token 检查，单个超长
     * CJK 段可一次性越限，"防评分面放大"弱于声明）。 */
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
            if (terms.size() >= MAX_TERMS) {
                return;
            }
            terms.add(token.substring(i, i + 2));
        }
    }

    private static boolean containsCjk(String token) {
        return token.codePoints().anyMatch(cp ->
                cp >= 0x4E00 && cp <= 0x9FFF || cp >= 0x3400 && cp <= 0x4DBF);
    }

    private record Match(int score, int distinctTerms) {
    }

    private static Match match(KbEntryRepository.KbEntryRecord entry, Set<String> terms) {
        int score = 0;
        int distinct = 0;
        String title = entry.title() == null ? "" : entry.title().toLowerCase(Locale.ROOT);
        String content = entry.content() == null ? "" : entry.content().toLowerCase(Locale.ROOT);
        for (String term : terms) {
            int hits = TITLE_WEIGHT * count(title, term) + CONTENT_WEIGHT * count(content, term);
            if (hits > 0) {
                distinct++;
                score += hits;
            }
        }
        return new Match(score, distinct);
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
