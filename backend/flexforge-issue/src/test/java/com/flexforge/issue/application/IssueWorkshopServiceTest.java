package com.flexforge.issue.application;

import com.flexforge.ai.model.ModelPort;
import com.flexforge.issue.domain.IssueWorkshopRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 需求工坊编排单测（FR-ISSUE-09）：追问/工具执行/创建后 clarify（成功与降级）/
 * 非法输出重试后 400/未知工具/参数校验反馈重试/会话隔离与清空。
 */
class IssueWorkshopServiceTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"), ZoneOffset.UTC);

    static class WorkshopStore implements IssueWorkshopRepository {
        final List<WorkshopMessageRecord> rows = new ArrayList<>();

        @Override
        public List<WorkshopMessageRecord> recentOf(String userId, int limit) {
            return rows.stream().filter(m -> m.userId().equals(userId))
                    .skip(Math.max(0, countOf(userId) - limit)).toList();
        }

        @Override
        public void insertExchange(WorkshopMessageRecord userMessage,
                                   WorkshopMessageRecord assistantMessage) {
            rows.add(userMessage);
            rows.add(assistantMessage);
        }

        @Override
        public int deleteAllOf(String userId) {
            int before = rows.size();
            rows.removeIf(m -> m.userId().equals(userId));
            return before - rows.size();
        }

        long countOf(String userId) {
            return rows.stream().filter(m -> m.userId().equals(userId)).count();
        }
    }

    /** 脚本模型：按序返回预设输出（供重试/工具路径编排）。 */
    static class ScriptedModel implements ModelPort {
        final List<String> outputs;
        final List<String> prompts = new ArrayList<>();
        int index;

        ScriptedModel(String... outputs) {
            this.outputs = List.of(outputs);
        }

        @Override
        public ModelReply complete(ModelRequest request) {
            prompts.add(request.prompt());
            return new ModelReply(outputs.get(Math.min(index++, outputs.size() - 1)));
        }

        @Override
        public String name() {
            return "scripted";
        }
    }

    /** 可编程 create_issue 工具（记录参数；标题可注入非法值）。 */
    static class RecordingTool implements WorkshopTool {
        final List<Map<String, String>> calls = new ArrayList<>();
        String forcedTitle = "演示需求";

        @Override
        public String name() {
            return "create_issue";
        }

        @Override
        public ToolResult execute(String operator, Map<String, String> args) {
            calls.add(Map.copyOf(args));
            String title = args.getOrDefault(TITLE_ARG, "");
            if (title.strip().length() > 120) {
                throw new IllegalArgumentException("标题超过 120 字符上限");
            }
            return new ToolResult("iss-1", title.isBlank() ? forcedTitle : title, "需求已创建");
        }
    }

    /** clarify 行为可编程的 IssueAiService 桩（构造器参数不触达）。 */
    static IssueAiService clarifyStub(boolean specProduced, boolean fail) {
        return new IssueAiService(null, null, null, null, CLOCK) {
            @Override
            public ClarifyOutcome clarify(String operator, String issueId, String answer) {
                if (fail) {
                    throw new IllegalStateException("clarify boom");
                }
                if (specProduced) {
                    return new ClarifyOutcome(true, List.of(), null,
                            (tools.jackson.databind.node.ObjectNode) null);
                }
                return new ClarifyOutcome(false, List.of("字段清单？", "验收标准？"), null, null);
            }
        };
    }

    private final WorkshopStore store = new WorkshopStore();

    private IssueWorkshopService service(ModelPort model, WorkshopTool tool,
                                         IssueAiService clarify) {
        return new IssueWorkshopService(new IssueWorkshopService.WorkshopKernel(
                store, clarify, model, event -> {
        }, CLOCK), tool == null ? List.of() : List.of(tool));
    }

    @Test
    void firstMessageAsksQuestionsAndPersistsPair() {
        IssueWorkshopService workshop = service(
                new ScriptedModel("{\"reply\":\"先确认字段与规则\"}"),
                new RecordingTool(), clarifyStub(true, false));
        IssueWorkshopService.WorkshopOutcome outcome =
                workshop.send("demo", "我想要一个设备点检的需求");
        assertThat(outcome.reply()).contains("先确认");
        assertThat(outcome.issueId()).isNull();
        assertThat(store.rows).hasSize(2);
        assertThat(store.rows.get(0).role()).isEqualTo("user");
        assertThat(store.rows.get(1).issueId()).isNull();
    }

    @Test
    void oversizedModelReplyIsCappedBeforePersist() {
        // 审查 P2-4：模型回复不受控——落库前截断到 8000（V021 CHECK 口径）
        String huge = "{\"reply\":\"" + "答".repeat(9000) + "\"}";
        IssueWorkshopService workshop = service(
                new ScriptedModel(huge), new RecordingTool(), clarifyStub(true, false));
        IssueWorkshopService.WorkshopOutcome outcome = workshop.send("demo", "消息");
        assertThat(store.rows.get(1).content().length())
                .isLessThanOrEqualTo(IssueWorkshopService.REPLY_STORE_MAX);
        assertThat(outcome.reply().length()).isLessThanOrEqualTo(9000);
        // 码点安全：截断不劈代理对
        String emojiReply = "{\"reply\":\"" + "😀".repeat(4001) + "\"}";
        WorkshopStore store2 = new WorkshopStore();
        IssueWorkshopService workshop2 = new IssueWorkshopService(
                new IssueWorkshopService.WorkshopKernel(store2, clarifyStub(true, false),
                        new ScriptedModel(emojiReply), event -> {
                        }, CLOCK),
                List.of());
        workshop2.send("demo", "消息");
        String stored = store2.rows.get(1).content();
        assertThat(Character.isHighSurrogate(stored.charAt(stored.length() - 1))).isFalse();
    }

    @Test
    void toolCallCreatesIssueAndSavesSpecViaClarify() {
        RecordingTool tool = new RecordingTool();
        IssueWorkshopService workshop = service(
                new ScriptedModel("{\"tool\":\"create_issue\",\"title\":\"设备点检管理\","
                        + "\"description\":\"字段：设备、结果、备注\"}"),
                tool, clarifyStub(true, false));
        IssueWorkshopService.WorkshopOutcome outcome =
                workshop.send("demo", "设备点检：设备、结果、备注，验收看列表能建");
        assertThat(tool.calls).hasSize(1);
        assertThat(tool.calls.get(0)).containsEntry("title", "设备点检管理");
        assertThat(outcome.issueId()).isEqualTo("iss-1");
        assertThat(outcome.reply()).contains("已创建需求《设备点检管理》");
        assertThat(outcome.reply()).contains("确认推送");
        assertThat(store.rows.get(1).issueId()).isEqualTo("iss-1");
    }

    @Test
    void clarifyFailureDegradesButKeepsCreatedIssue() {
        IssueWorkshopService workshop = service(
                new ScriptedModel("{\"tool\":\"create_issue\",\"title\":\"t\",\"description\":\"d\"}"),
                new RecordingTool(), clarifyStub(false, true));
        IssueWorkshopService.WorkshopOutcome outcome = workshop.send("demo", "第二个需求");
        assertThat(outcome.issueId()).isEqualTo("iss-1");
        assertThat(outcome.reply()).contains("规格生成未完成");
    }

    @Test
    void clarifyStillAskingDegradesToHint() {
        IssueWorkshopService workshop = service(
                new ScriptedModel("{\"tool\":\"create_issue\",\"title\":\"t\",\"description\":\"d\"}"),
                new RecordingTool(), clarifyStub(false, false));
        IssueWorkshopService.WorkshopOutcome outcome = workshop.send("demo", "第三个需求");
        assertThat(outcome.reply()).contains("字段清单");
    }

    @Test
    void invalidOutputRetriedOnceThenRejected() {
        IssueWorkshopService workshop = service(
                new ScriptedModel("不是JSON", "还是不是JSON"),
                new RecordingTool(), clarifyStub(true, false));
        assertThatThrownBy(() -> workshop.send("demo", "消息"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.rows).isEmpty();
    }

    @Test
    void unknownToolRetriedThenRejected() {
        IssueWorkshopService workshop = service(
                new ScriptedModel("{\"tool\":\"delete_everything\"}"),
                new RecordingTool(), clarifyStub(true, false));
        assertThatThrownBy(() -> workshop.send("demo", "消息"))
                .hasMessageContaining("不合法");
        assertThat(store.rows).isEmpty();
    }

    @Test
    void invalidToolArgumentsFeedBackAndRetrySucceeds() {
        RecordingTool tool = new RecordingTool();
        IssueWorkshopService workshop = service(
                new ScriptedModel(
                        "{\"tool\":\"create_issue\",\"title\":\"" + "长".repeat(130)
                                + "\",\"description\":\"d\"}",
                        "{\"tool\":\"create_issue\",\"title\":\"合法标题\",\"description\":\"d\"}"),
                tool, clarifyStub(true, false));
        IssueWorkshopService.WorkshopOutcome outcome = workshop.send("demo", "消息");
        // 首次调用（非法标题）被校验拒绝后经反馈重试，第二次执行成功
        assertThat(tool.calls).hasSize(2);
        assertThat(tool.calls.get(1)).containsEntry("title", "合法标题");
        assertThat(outcome.issueId()).isEqualTo("iss-1");
    }

    @Test
    void oversizeDescriptionIsRejectedWithFeedback() {
        // P2-5：description 服务端 4000 上限（此前仅前端表单约束）
        RecordingTool tool = new RecordingTool() {
            @Override
            public ToolResult execute(String operator, Map<String, String> args) {
                calls.add(Map.copyOf(args));
                if (args.getOrDefault(WorkshopTool.DESCRIPTION_ARG, "").length() > 4000) {
                    throw new IllegalArgumentException("description ≤4000 字符");
                }
                return new ToolResult("iss-2", "ok", "需求已创建");
            }
        };
        IssueWorkshopService workshop = service(
                new ScriptedModel(
                        "{\"tool\":\"create_issue\",\"title\":\"t\",\"description\":\""
                                + "描".repeat(4001) + "\"}",
                        "{\"tool\":\"create_issue\",\"title\":\"t\",\"description\":\"短\"}"),
                tool, clarifyStub(true, false));
        IssueWorkshopService.WorkshopOutcome outcome = workshop.send("demo", "消息");
        assertThat(tool.calls).hasSize(2);
        assertThat(outcome.issueId()).isEqualTo("iss-2");
    }

    @Test
    void messageCapsIsolationAndClear() {
        IssueWorkshopService workshop = service(
                new ScriptedModel("{\"reply\":\"ok\"}"),
                new RecordingTool(), clarifyStub(true, false));
        assertThatThrownBy(() -> workshop.send("demo", " ".repeat(2001)))
                .isInstanceOf(IllegalArgumentException.class);
        workshop.send("demo", "第一条");
        assertThat(workshop.messagesOf("other")).isEmpty();
        assertThat(workshop.messagesOf("demo")).hasSize(2);
        assertThat(workshop.clear("demo")).isEqualTo(2);
        assertThat(store.rows).isEmpty();
    }

    @Test
    void historyIsInjectedIntoPromptAsData() {
        ScriptedModel model = new ScriptedModel("{\"reply\":\"第二轮\"}");
        IssueWorkshopService workshop = service(model, new RecordingTool(),
                clarifyStub(true, false));
        workshop.send("demo", "第一条");
        workshop.send("demo", "第二条");
        assertThat(model.prompts.get(1)).contains("用户：第一条");
        assertThat(model.prompts.get(1)).contains("助手：");
    }
}
