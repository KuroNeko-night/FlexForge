package com.flexforge.kb.application;

import com.flexforge.ai.model.FixtureModelPort;
import com.flexforge.ai.model.ModelPort;
import com.flexforge.ai.model.ModelUnavailableException;
import com.flexforge.kb.domain.FakeKbRepositories;
import com.flexforge.kb.domain.KbChatRepository;
import com.flexforge.kb.domain.KbEntryRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 助手编排单测（FR-KB-02/03/04）：检索注入与引用快照、fixture 确定性回答
 * （命中引用标题/未命中明示暂无）、模型失败不落半截会话且审计失败、提问校验、
 * 会话隔离与清空、注入预算裁剪、多轮历史进提示词。
 */
class KbAssistantServiceTest {

    private final FakeKbRepositories.EntryStore entries = new FakeKbRepositories.EntryStore();
    private final FakeKbRepositories.ChatStore chat = new FakeKbRepositories.ChatStore();
    private final FakeKbRepositories.AuditSink audit = new FakeKbRepositories.AuditSink();

    /** 录制提示词的固定回答桩（失败场景由 failing 标记切换；空回复场景子类覆写）。 */
    static class RecordingModelPort implements ModelPort {
        final List<String> prompts = new java.util.ArrayList<>();
        boolean failing;

        @Override
        public ModelReply complete(ModelRequest request) {
            prompts.add(request.prompt());
            if (failing) {
                throw new IllegalStateException("boom");
            }
            return new ModelReply("固定回答");
        }

        @Override
        public String name() {
            return "recording-stub";
        }
    }

    private KbAssistantService service(ModelPort model) {
        return service(model, java.util.List.of());
    }

    private KbAssistantService service(ModelPort model,
                                       java.util.List<AssistantTool> toolBeans) {
        return new KbAssistantService(
                new KbAssistantService.KbKernel(
                        entries, chat, model, audit, FakeKbRepositories.FIXED_CLOCK),
                toolBeans, stubBusinessTools("采购订单(purchase_order)、图书(library_book)"));
    }

    /** 固定实体索引的业务工具桩（元数据/记录查询在 API 级用真实实现覆盖）。 */
    static BusinessEntityTools stubBusinessTools(String index) {
        return new BusinessEntityTools(null, null) {
            @Override
            public String entityIndex() {
                return index;
            }
        };
    }

    private void seed(String title, String category, String content) {
        entries.rows.put(title, new KbEntryRepository.KbEntryRecord(
                "kb-" + title, title, category, content, "admin", null));
    }

    @Test
    void fixtureAnswerQuotesMatchedEntriesAndPersistsPair() {
        seed("差旅报销规范", "财务制度", "员工出差后 30 日内提交报销单……");
        seed("平台使用入门", "使用教程", "进入工作台后选择业务应用……");
        KbAssistantService.AskOutcome outcome =
                service(new FixtureModelPort()).ask("demo", "怎么报销差旅费用");
        assertThat(outcome.answer()).contains("差旅报销规范");
        assertThat(outcome.references()).singleElement()
                .satisfies(r -> {
                    assertThat(r.id()).isEqualTo("kb-差旅报销规范");
                    assertThat(r.category()).isEqualTo("财务制度");
                });
        assertThat(chat.rows).hasSize(2);
        assertThat(chat.rows.get(0).role()).isEqualTo("user");
        assertThat(chat.rows.get(0).content()).isEqualTo("怎么报销差旅费用");
        assertThat(chat.rows.get(1).role()).isEqualTo("assistant");
        assertThat(chat.rows.get(1).referencesJson()).contains("差旅报销规范");
        assertThat(audit.events).singleElement()
                .satisfies(e -> assertThat(e.result()).isEqualTo("success"));
    }

    @Test
    void fixtureNoHitAnswerStatesMissingInsteadOfInventing() {
        seed("差旅报销规范", null, "与问题无关的内容");
        KbAssistantService.AskOutcome outcome =
                service(new FixtureModelPort()).ask("demo", "量子力学入门");
        assertThat(outcome.answer()).contains("暂无");
        assertThat(outcome.references()).isEmpty();
        assertThat(chat.rows.get(1).referencesJson()).isEqualTo("[]");
    }

    @Test
    void modelFailurePersistsNothingAndAuditsFailure() {
        seed("差旅报销规范", null, "内容");
        RecordingModelPort port = new RecordingModelPort();
        port.failing = true;
        assertThatThrownBy(() -> service(port).ask("demo", "报销流程"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(chat.rows).isEmpty();
        assertThat(audit.events).singleElement()
                .satisfies(e -> {
                    assertThat(e.action()).isEqualTo("kb.ask");
                    assertThat(e.result()).isEqualTo("failure");
                });
    }

    @Test
    void emptyModelAnswerIs503SemanticsWithoutPartialConversation() {
        seed("差旅报销规范", null, "内容");
        RecordingModelPort blank = new RecordingModelPort() {
            @Override
            public ModelReply complete(ModelRequest request) {
                super.complete(request);
                return new ModelReply("   ");
            }
        };
        assertThatThrownBy(() -> service(blank).ask("demo", "报销流程"))
                .isInstanceOf(ModelUnavailableException.class);
        assertThat(chat.rows).isEmpty();
        assertThat(audit.events).singleElement()
                .satisfies(e -> assertThat(e.result()).isEqualTo("failure"));
    }

    @Test
    void truncateAtCodePointKeepsSurrogatePairsWhole() {
        String emoji = "😀".repeat(4001);
        String clipped = KbAssistantService.truncateAtCodePoint(emoji, 8000);
        // 偶数边界恰好落在代理对之后；尾字符必须是完整对的低位（不得残留孤立高位）
        assertThat(clipped.length()).isEqualTo(8000);
        assertThat(Character.isHighSurrogate(clipped.charAt(clipped.length() - 1))).isFalse();
        // 奇数边界落在代理对中间 → 回退一位保整对
        String retreated = KbAssistantService.truncateAtCodePoint(emoji, 8001);
        assertThat(retreated.length()).isEqualTo(8000);
        assertThat(KbAssistantService.truncateAtCodePoint("短文本", 100)).isEqualTo("短文本");
    }

    @Test
    void questionValidationAndHistoryIsolation() {
        KbAssistantService assistant = service(new FixtureModelPort());
        assertThatThrownBy(() -> assistant.ask("demo", "  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> assistant.ask("demo", "问".repeat(2001)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2000");
        seed("平台使用入门", "使用教程", "内容");
        assistant.ask("demo", "怎么进入工作台");
        assertThat(assistant.messagesOf("other")).isEmpty();
        assertThat(assistant.messagesOf("demo")).hasSize(2);
        assertThat(assistant.clear("other")).isZero();
        assertThat(chat.rows).hasSize(2);
        assertThat(assistant.clear("demo")).isEqualTo(2);
        assertThat(chat.rows).isEmpty();
    }

    @Test
    void promptEmbedsQuestionHistoryAndCappedKnowledge() {
        seed("超长条目", "制度", "长".repeat(30_000));
        seed("另一条目", null, "短内容");
        RecordingModelPort port = new RecordingModelPort();
        KbAssistantService assistant = service(port);
        assistant.ask("demo", "超长条目 内容");
        assistant.ask("demo", "另一条目");
        String first = port.prompts.get(0);
        assertThat(first).contains("## 知识库参考（数据）");
        assertThat(first).contains("### [制度] 超长条目");
        assertThat(first).contains("（截断）");
        String knowledge = section(first);
        assertThat(knowledge.length()).isLessThanOrEqualTo(
                KbAssistantService.KNOWLEDGE_MAX_CHARS);
        // 第二轮提示词携带第一轮问答（多轮上下文）
        assertThat(section(port.prompts.get(1), "## 会话历史（数据）"))
                .contains("超长条目 内容")
                .contains("固定回答");
    }

    @Test
    void clipOldestLinesKeepsTailUnderBudget() {
        String history = "用户：第一轮\n助手：回答一\n用户：第二轮\n助手：回答二\n";
        String clipped = KbAssistantService.clipOldestLines(history, 20);
        assertThat(clipped.length()).isLessThanOrEqualTo(20);
        assertThat(clipped).endsWith("回答二\n");
        assertThat(clipped).doesNotContain("第一轮");
        assertThat(KbAssistantService.clipOldestLines("短", 100)).isEqualTo("短");
    }

    @Test
    void multiTurnExchangeAccumulatesInOrder() {
        seed("报销规范", null, "内容");
        KbAssistantService assistant = service(new FixtureModelPort());
        assistant.ask("demo", "报销要多久");
        assistant.ask("demo", "那发票呢");
        List<KbChatRepository.KbMessageRecord> recent = chat.recentOf("demo", 8);
        assertThat(recent).hasSize(4);
        assertThat(recent).extracting(KbChatRepository.KbMessageRecord::role)
                .containsExactly("user", "assistant", "user", "assistant");
        assertThat(recent.get(2).content()).isEqualTo("那发票呢");
    }

    @Test
    void attachmentsAreValidatedExtractedInjectedAndPersisted() {
        seed("报销规范", null, "内容");
        RecordingModelPort port = new RecordingModelPort();
        KbAssistantService assistant = service(port);
        KbAssistantService.AskOutcome outcome = assistant.ask("demo", "帮我核对附件里的数据", List.of(
                new KbAssistantService.IncomingAttachment(
                        "报销单.csv", "text/csv", "物料,金额\nA01,300".getBytes()),
                new KbAssistantService.IncomingAttachment(
                        "截图.png", "image/png", new byte[] {1, 2, 3})));
        // 提示词数据段含提取文本与图片标注；附件行随用户消息落库
        assertThat(port.prompts.get(0)).contains("## 附件参考（数据）");
        assertThat(port.prompts.get(0)).contains("### 报销单.csv（csv）");
        assertThat(port.prompts.get(0)).contains("A01,300");
        assertThat(port.prompts.get(0)).contains("### 截图.png（png，图片未提取文本）");
        assertThat(outcome.attachments()).hasSize(2);
        assertThat(chat.attachments.size()).isEqualTo(2);
        KbChatRepository.KbAttachmentRecord csv = chat.attachments.get(0);
        assertThat(csv.filename()).isEqualTo("报销单.csv");
        assertThat(csv.extractedText()).contains("A01");
        assertThat(chat.attachments.get(1).extractedText()).isNull();
        // 回放与归属
        assertThat(assistant.attachmentsOf(
                List.of(chat.rows.get(0).id()))).hasSize(2);
        assertThat(assistant.findOwnedAttachment(csv.id()).ownerId()).isEqualTo("demo");
    }

    @Test
    void fixtureAcknowledgesAttachmentsWhenNoKnowledgeHit() {
        KbAssistantService.AskOutcome outcome = service(new FixtureModelPort()).ask(
                "demo", "总结一下", List.of(new KbAssistantService.IncomingAttachment(
                        "报告.pdf", "application/pdf", new byte[] {1, 2, 3})));
        assertThat(outcome.answer()).contains("暂无");
        assertThat(outcome.answer()).contains("已收到附件：报告.pdf");
    }

    @Test
    void attachmentCountAndWhitelistAreEnforced() {
        KbAssistantService assistant = service(new FixtureModelPort());
        assertThatThrownBy(() -> assistant.ask("demo", "q", List.of(
                new KbAssistantService.IncomingAttachment("a.txt", "text/plain", "x".getBytes()),
                new KbAssistantService.IncomingAttachment("b.txt", "text/plain", "x".getBytes()),
                new KbAssistantService.IncomingAttachment("c.txt", "text/plain", "x".getBytes()),
                new KbAssistantService.IncomingAttachment("d.txt", "text/plain", "x".getBytes()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3 个附件");
        assertThatThrownBy(() -> assistant.ask("demo", "q", List.of(
                new KbAssistantService.IncomingAttachment("a.exe", "application/x-msdownload",
                        "x".getBytes()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的附件类型");
        assertThat(chat.rows).isEmpty();
        assertThat(chat.attachments).isEmpty();
    }

    @Test
    void nearLimitAttachmentIsNeverDroppedSilently() {
        // 审查 P2-1：提取接近 20000 上限时不得整体丢弃并谎报"（无附件）"
        String huge = "内".repeat(KbAssistantService.ATTACHMENTS_MAX_CHARS - 10);
        String text = KbAssistantService.attachmentsText(List.of(
                new KbAssistantService.Prepared("大附件.txt", "text/plain", huge.length(),
                        huge.getBytes(), huge),
                new KbAssistantService.Prepared("第二个.txt", "text/plain", 1,
                        "x".getBytes(), "x")));
        assertThat(text).startsWith("### 大附件.txt");
        assertThat(text).doesNotContain("（无附件）");
        assertThat(text.length()).isLessThanOrEqualTo(KbAssistantService.ATTACHMENTS_MAX_CHARS + 30);
        assertThat(text).contains("已省略");
    }

    @Test
    void filenameSanitizeShrinkAndWhitelistOrdering() {
        // 控制字符剥离（P3-2）+ 超长名保扩展名（P3-1：白名单按原始名判定）
        assertThat(KbAssistantService.sanitizeFilename("..\\..\\ev\r\nil.pdf")).isEqualTo("evil.pdf");
        String longName = "长".repeat(300) + ".pdf";
        String shrunk = KbAssistantService.shrinkFilename(longName);
        assertThat(shrunk.length()).isLessThanOrEqualTo(255);
        assertThat(shrunk).endsWith(".pdf");
        KbAssistantService assistant = service(new FixtureModelPort());
        assistant.ask("demo", "q", List.of(new KbAssistantService.IncomingAttachment(
                longName, "application/pdf", "x".getBytes())));
        assertThat(chat.attachments.get(0).filename()).endsWith(".pdf");
    }

    @Test
    void businessToolLoopExecutesToolAndFeedsResultBack() {
        // 桩模型：首轮输出工具调用 JSON，次轮基于工具结果作答
        RecordingModelPort port = new RecordingModelPort() {
            int round;

            @Override
            public ModelReply complete(ModelRequest request) {
                super.complete(request);
                return new ModelReply(round++ == 0
                        ? "{\"tool\":\"inspect_entity\",\"entity\":\"purchase_order\"}"
                        : "根据平台业务结构：采购订单包含单号、金额等字段。");
            }
        };
        final String[] received = new String[1];
        AssistantTool inspect = new AssistantTool() {
            @Override
            public String name() {
                return "inspect_entity";
            }

            @Override
            public String description() {
                return "test";
            }

            @Override
            public String apply(java.util.Map<String, String> parameters) {
                received[0] = parameters.get("entity");
                return "业务：采购订单(purchase_order)\n- 单号(order_no)：text，必填";
            }
        };
        KbAssistantService.AskOutcome outcome =
                service(port, List.of(inspect)).ask("demo", "采购订单业务有哪些字段");
        assertThat(received[0]).isEqualTo("purchase_order");
        // 第二轮提示词携带工具结果数据段（供模型引用）
        assertThat(port.prompts.get(1)).contains("## 工具结果（数据）");
        assertThat(port.prompts.get(1)).contains("单号(order_no)");
        assertThat(outcome.answer()).contains("采购订单包含单号");
        assertThat(chat.rows).hasSize(2);
    }

    @Test
    void fixtureAnswersBusinessStructureQuestionsViaTools() {
        // fixture 脚本：命中实体索引 → 工具调用 → 工具结果确定性作答
        class RecordingFixture extends FixtureModelPort {
            final List<String> prompts = new java.util.ArrayList<>();

            @Override
            public ModelReply complete(ModelRequest request) {
                prompts.add(request.prompt());
                return super.complete(request);
            }
        }
        RecordingFixture fixture = new RecordingFixture();
        AssistantTool inspect = new AssistantTool() {
            @Override
            public String name() {
                return "inspect_entity";
            }

            @Override
            public String description() {
                return "test";
            }

            @Override
            public String apply(java.util.Map<String, String> parameters) {
                return "业务：采购订单(purchase_order)\n- 单号(order_no)：text，必填\n- 金额(amount)：number";
            }
        };
        KbAssistantService.AskOutcome outcome = service(fixture, List.of(inspect))
                .ask("demo", "采购订单业务有哪些内容");
        assertThat(fixture.prompts).hasSize(2);
        assertThat(fixture.prompts.get(1)).contains("## 工具结果（数据）");
        assertThat(outcome.answer()).contains("平台业务结构");
        assertThat(outcome.answer()).contains("业务实体元数据");
    }

    /** 截取提示词某数据段（标记起至下一 "## " 段或文末）。 */
    private static String section(String prompt, String marker) {
        int start = prompt.indexOf(marker);
        assertThat(start).isGreaterThanOrEqualTo(0);
        int from = start + marker.length();
        int end = prompt.indexOf("\n## ", from);
        return end > from ? prompt.substring(from, end) : prompt.substring(from);
    }

    private static String section(String prompt) {
        return section(prompt, "## 知识库参考（数据）");
    }
}
