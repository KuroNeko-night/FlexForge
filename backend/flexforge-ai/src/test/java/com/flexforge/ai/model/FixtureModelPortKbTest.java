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

    @Test
    void businessQuestionTriggersInspectToolCallFromEntityIndex() {
        String prompt = "头部\n"
                + SECTION + "\n" + FixtureModelPort.KB_NO_HIT + "\n"
                + FixtureModelPort.KB_ATTACHMENTS_MARKER + "\n" + FixtureModelPort.KB_NO_ATTACHMENTS + "\n"
                + FixtureModelPort.KB_ENTITY_INDEX_MARKER
                + "\n采购订单(purchase_order)、图书借阅(library_book)\n"
                + QUESTION + "\n采购订单业务有哪些字段";
        assertThat(FixtureModelPort.kbAnswer(prompt))
                .isEqualTo("{\"tool\":\"inspect_entity\",\"entity\":\"purchase_order\"}");
    }

    @Test
    void businessListQuestionTriggersListTool() {
        String prompt = FixtureModelPort.KB_ENTITY_INDEX_MARKER
                + "\n采购订单(purchase_order)\n" + QUESTION + "\n平台上有哪些业务";
        assertThat(FixtureModelPort.kbAnswer(prompt))
                .isEqualTo("{\"tool\":\"list_entities\"}");
    }

    @Test
    void emptyEntityIndexDoesNotTriggerTools() {
        String prompt = FixtureModelPort.KB_ENTITY_INDEX_MARKER
                + "\n（暂无业务实体）\n" + QUESTION + "\n有哪些业务";
        assertThat(FixtureModelPort.kbAnswer(prompt)).contains("暂无");
    }

    @Test
    void toolResultIsSummarizedDeterministically() {
        String prompt = "头部\n" + FixtureModelPort.KB_TOOL_RESULT_MARKER
                + "\n[inspect_entity]\n业务：采购订单(purchase_order)\n- 单号(order_no)：text，必填\n\n"
                + QUESTION + "\n刚才那个问题";
        assertThat(FixtureModelPort.kbAnswer(prompt))
                .contains("平台业务结构")
                .contains("采购订单")
                .contains("业务实体元数据");
    }

    @Test
    void listToolResultIsSummarizedNotDropped() {
        // 审查 P2-6：list_entities 的索引行（半角括号顿号连接）此前被全角条件全灭
        String prompt = FixtureModelPort.KB_TOOL_RESULT_MARKER
                + "\n[list_entities]\n采购订单(purchase_order)、图书借阅(library_book)\n\n"
                + QUESTION + "\n刚才那个问题";
        assertThat(FixtureModelPort.kbAnswer(prompt))
                .contains("采购订单(purchase_order)")
                .contains("图书借阅(library_book)")
                .doesNotContain("[list_entities]");
    }

    @Test
    void entityPairRegexHandlesDisplayNameWithParentheses() {
        // 审查 P3-4：displayName 含括号段（"v2"）不吞并相邻实体
        String prompt = FixtureModelPort.KB_ENTITY_INDEX_MARKER
                + "\n采购订单(v2)(purchase_order)、图书(library_book)\n"
                + QUESTION + "\n采购订单业务有哪些字段";
        assertThat(FixtureModelPort.kbAnswer(prompt))
                .isEqualTo("{\"tool\":\"inspect_entity\",\"entity\":\"purchase_order\"}");
    }

    @Test
    void recordDataQuestionTriggersQueryRecordsCall() {
        // P31：到货类记录提问优先于结构类判定（同句含实体名+数据关键词）
        String prompt = FixtureModelPort.KB_ENTITY_INDEX_MARKER
                + "\n采购订单(purchase_order)、图书借阅(library_book)\n"
                + QUESTION + "\n采购订单 PO-001 预计什么时候到货";
        assertThat(FixtureModelPort.kbAnswer(prompt))
                .isEqualTo("{\"tool\":\"query_records\",\"entity\":\"purchase_order\"}");
    }

    @Test
    void recordDataQuestionWithoutEntityFallsBackToStructureOrPlainAnswer() {
        String prompt = FixtureModelPort.KB_ENTITY_INDEX_MARKER
                + "\n采购订单(purchase_order)\n"
                + QUESTION + "\n快递什么时候到货";
        assertThat(FixtureModelPort.kbAnswer(prompt))
                .doesNotContain("query_records");
    }

    @Test
    void structureQuestionWithRecordKeywordStillRoutesToInspect() {
        // 审查 P3-4："金额字段是什么类型"是结构问句，不因关键词误入记录查询
        String prompt = FixtureModelPort.KB_ENTITY_INDEX_MARKER
                + "\n采购订单(purchase_order)\n"
                + QUESTION + "\n采购订单的金额字段是什么类型";
        assertThat(FixtureModelPort.kbAnswer(prompt))
                .isEqualTo("{\"tool\":\"inspect_entity\",\"entity\":\"purchase_order\"}");
    }

    @Test
    void recordToolResultLineStartMarkerOnlyCounts() {
        // 审查 P3-5：字段值行内出现"业务数据："字面量不改写元数据结果口径
        String prompt = FixtureModelPort.KB_TOOL_RESULT_MARKER
                + "\n[inspect_entity]\n业务：采购订单(purchase_order)\n- 备注(note)：业务数据：见附表\n";
        assertThat(FixtureModelPort.kbAnswer(prompt))
                .contains("业务实体元数据")
                .doesNotContain("真实数据记录");
    }

    @Test
    void recordToolResultIsAnsweredInDataProvenanceTone() {
        // P31：「业务数据：」标记头 → 真实记录口径作答（区别于元数据口径）
        String prompt = FixtureModelPort.KB_TOOL_RESULT_MARKER
                + "\n[query_records]\n业务数据：采购订单(purchase_order) 共 1 条记录（按创建时间倒序，显示前 1 条）：\n"
                + "- 编号 rec-1，单号=PO-001，预计到货日期=2026-09-30\n\n"
                + QUESTION + "\n刚才那个问题";
        assertThat(FixtureModelPort.kbAnswer(prompt))
                .contains("真实数据记录")
                .contains("单号=PO-001")
                .contains("实时查询");
    }

    @Test
    void workshopFirstMessageAsksAndSecondTriggersCreateIssue() {
        String first = FixtureModelPort.WORKSHOP_HISTORY_MARKER + "\n（无）\n"
                + FixtureModelPort.WORKSHOP_MESSAGE_MARKER + "\n我想要个点检需求";
        assertThat(FixtureModelPort.workshopAnswer(first)).contains("\"reply\"").contains("字段");

        String second = FixtureModelPort.WORKSHOP_HISTORY_MARKER
                + "\n用户：我想要个点检需求\n助手：好的…\n"
                + FixtureModelPort.WORKSHOP_MESSAGE_MARKER + "\n字段有设备与结果，验收可建可筛";
        assertThat(FixtureModelPort.workshopAnswer(second))
                .contains("\"tool\":\"create_issue\"")
                .contains("字段有设备与结果");
    }
}
