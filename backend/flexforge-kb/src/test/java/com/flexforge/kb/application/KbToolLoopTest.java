package com.flexforge.kb.application;

import com.flexforge.ai.model.FixtureModelPort;
import com.flexforge.ai.model.ModelPort;
import com.flexforge.data.application.DynamicRecordService;
import com.flexforge.data.domain.RecordEntry;
import com.flexforge.kb.domain.FakeKbRepositories;
import com.flexforge.meta.application.MetaRegistry;
import com.flexforge.meta.domain.EntityStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 助手工具环路边界单测（FR-KB-07/08，审查 P2-7 补锁）：额度与轮次硬上限、
 * 额度耗尽提示回灌、未知工具错误文本回灌不中断对话、query_records 组合路径
 * （fixture 产出工具调用 → 真实工具执行 → 结果回灌 → 数据口径作答，P3-3）。
 */
class KbToolLoopTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final FakeKbRepositories.EntryStore entries = new FakeKbRepositories.EntryStore();
    private final FakeKbRepositories.ChatStore chat = new FakeKbRepositories.ChatStore();
    private final FakeKbRepositories.AuditSink audit = new FakeKbRepositories.AuditSink();

    private KbAssistantService service(ModelPort model, List<AssistantTool> tools) {
        return new KbAssistantService(
                new KbAssistantService.KbKernel(
                        entries, chat, model, audit, FakeKbRepositories.FIXED_CLOCK),
                tools, KbAssistantServiceTest.stubBusinessTools("采购订单(purchase_order)"));
    }

    private static AssistantTool listTool() {
        return new AssistantTool() {
            @Override
            public String name() {
                return "list_entities";
            }

            @Override
            public String description() {
                return "test";
            }

            @Override
            public String apply(java.util.Map<String, String> parameters) {
                return "采购订单(purchase_order)、图书(library_book)";
            }
        };
    }

    @BeforeEach
    void reset() {
        entries.rows.clear();
        chat.rows.clear();
        audit.events.clear();
    }

    @Test
    void budgetAndRoundLimitsAreEnforced() {
        // 模型永远输出工具 JSON：额度提示回灌，4 轮后 400（审查 P2-7 补锁）
        ScriptedModel looping = new ScriptedModel("{\"tool\":\"list_entities\"}");
        assertThatThrownBy(() -> service(looping, List.of(listTool())).ask("demo", "有哪些业务"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最终回答");
        assertThat(looping.prompts).hasSize(4);
        // 额度耗尽后的提示词携带提示与工具结果（数据段回灌）
        assertThat(looping.prompts.get(3)).contains("额度已用完");
        assertThat(looping.prompts.get(3)).contains("采购订单(purchase_order)");
        assertThat(chat.rows).isEmpty();
    }

    @Test
    void unknownToolIsFedBackAsDataAndConversationSurvives() {
        ScriptedModel port = new ScriptedModel(
                "{\"tool\":\"drop_database\"}", "不能执行该操作，我可以帮你查询业务结构。");
        KbAssistantService.AskOutcome outcome = service(port, List.of()).ask("demo", "删库");
        assertThat(port.prompts.get(1)).contains("错误：未知工具 drop_database");
        assertThat(outcome.answer()).contains("业务结构");
        assertThat(chat.rows).hasSize(2);
    }

    @Test
    void recordQuestionRunsRealQueryRecordsToolThroughFixtureLoop() {
        // 组合路径（审查 P3-3）：fixture 首轮产出 query_records JSON → 真实
        // QueryRecordsTool（DynamicRecordService 白名单取数）执行 → 工具结果
        // 数据段回灌 → fixture 次轮按真实记录口径作答
        BusinessEntityToolsTest.StubRepository meta = new BusinessEntityToolsTest.StubRepository();
        meta.rows.add(BusinessEntityToolsTest.entity("purchase_order", "采购订单", EntityStatus.ENABLED));
        BusinessEntityToolsTest.StubRecordRepository data = new BusinessEntityToolsTest.StubRecordRepository();
        data.rows.add(new RecordEntry("rec-1", "e-purchase_order",
                JSON.readTree("{\"code\":\"PO-001\",\"qty\":5}"), Instant.EPOCH, Instant.EPOCH));
        MetaRegistry registry = new MetaRegistry(meta);
        BusinessEntityTools real = new BusinessEntityTools(registry, new DynamicRecordService(
                registry, data, event -> { }, Clock.systemUTC()));
        KbAssistantService assistant = new KbAssistantService(
                new KbAssistantService.KbKernel(
                        entries, chat, new FixtureModelPort(), audit, FakeKbRepositories.FIXED_CLOCK),
                List.of(new BusinessEntityTools.QueryRecordsTool(real)), real);

        KbAssistantService.AskOutcome outcome =
                assistant.ask("demo", "采购订单 PO-001 预计什么时候到货");

        assertThat(outcome.answer()).contains("真实数据记录");
        assertThat(outcome.answer()).contains("单号=PO-001");
        assertThat(chat.rows).hasSize(2);
    }

    /** 脚本模型：按序返回预设输出（末位重复兜底）。 */
    static class ScriptedModel implements ModelPort {
        final List<String> prompts = new java.util.ArrayList<>();
        final List<String> outputs;
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
}
