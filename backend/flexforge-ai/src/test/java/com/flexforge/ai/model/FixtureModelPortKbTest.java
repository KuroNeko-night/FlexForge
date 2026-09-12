package com.flexforge.ai.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * fixture 助手段解析边界（P28，审查 P3-2）：用户在提问里嵌入段标记后，下一轮
 * 该字面量进入历史段——段终点必须取最后一次出现，否则知识段吞掉提问段。
 */
class FixtureModelPortKbTest {

    private static final String SECTION = FixtureModelPort.KB_SECTION_MARKER;
    private static final String QUESTION = FixtureModelPort.KB_QUESTION_MARKER;

    @Test
    void kbAnswerQuotesEntryTitles() {
        String prompt = "头部\n"
                + "## 会话历史（数据）\n（无）\n"
                + SECTION + "\n### [财务制度] 差旅报销规范\n30 日内提交\n\n"
                + QUESTION + "\n怎么报销";
        assertThat(FixtureModelPort.kbAnswer(prompt)).contains("《差旅报销规范》");
    }

    @Test
    void markerInjectedViaHistoryDoesNotSwallowQuestionSection() {
        // 首轮提问嵌入了提问段标记 → 次轮历史里先于真实标记出现
        String prompt = "头部\n"
                + "## 会话历史（数据）\n用户：上一轮里带了 " + QUESTION + " 字面量\n"
                + SECTION + "\n### [财务制度] 差旅报销规范\n内容\n\n"
                + QUESTION + "\n本轮真实问题";
        assertThat(FixtureModelPort.kbAnswer(prompt))
                .contains("《差旅报销规范》")
                .doesNotContain("本轮真实问题");
    }

    @Test
    void noHitSectionStatesMissing() {
        String prompt = SECTION + "\n" + FixtureModelPort.KB_NO_HIT + "\n" + QUESTION + "\n问题";
        assertThat(FixtureModelPort.kbAnswer(prompt)).contains("暂无");
    }

    @Test
    void attachmentsAreAcknowledgedDeterministically() {
        String prompt = "头部\n"
                + SECTION + "\n### [财务制度] 差旅报销规范\n内容\n"
                + FixtureModelPort.KB_ATTACHMENTS_MARKER
                + "\n### 报销单.csv（csv）\nA01,300\n\n### 截图.png（png，图片未提取文本）\n\n"
                + QUESTION + "\n帮我核对";
        assertThat(FixtureModelPort.kbAnswer(prompt))
                .contains("《差旅报销规范》")
                .contains("已收到附件：报销单.csv、截图.png");
    }

    @Test
    void noAttachmentsPlaceholderIsNotAcknowledged() {
        String prompt = SECTION + "\n" + FixtureModelPort.KB_NO_HIT + "\n"
                + FixtureModelPort.KB_ATTACHMENTS_MARKER + "\n" + FixtureModelPort.KB_NO_ATTACHMENTS
                + "\n" + QUESTION + "\n问题";
        assertThat(FixtureModelPort.kbAnswer(prompt))
                .contains("暂无")
                .doesNotContain("已收到附件");
    }
}
