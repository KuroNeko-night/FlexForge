package com.flexforge.ai.spec;

import com.flexforge.ai.model.ModelPort;
import com.flexforge.ai.model.ModelUnavailableException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 澄清引擎（RB-AI：非法 JSON/Schema 违约重试与上限）。 */
class ClarifyEngineTest {

    /** v3 契约：规格轮输出须同轮携带三段简报 brief。 */
    private static final String VALID_SPEC = """
            {"spec":{"schemaVersion":1,"summary":"s","entities":[
            {"name":"a_item","displayName":"A","fields":[
            {"name":"name","displayName":"N","fieldType":"text","required":true}]}],
            "acceptance":["可查询"]},
            "brief":{"colloquial":"c","feasibility":"f","agentPrompt":"a"}}
            """;

    @Test
    void questionsRoundThenSpecRound() {
        AtomicInteger calls = new AtomicInteger();
        ModelPort scripted = new ModelPort() {
            @Override
            public ModelReply complete(ModelRequest request) {
                return calls.incrementAndGet() == 1
                        ? new ModelReply("{\"questions\":[\"需要哪些字段?\"]}")
                        : new ModelReply(VALID_SPEC);
            }

            @Override
            public String name() {
                return "scripted";
            }
        };
        ClarifyEngine.ClarifyResult first = new ClarifyEngine(scripted).clarify("p1");
        assertThat(first.specProduced()).isFalse();
        assertThat(first.questions()).containsExactly("需要哪些字段?");

        ClarifyEngine.ClarifyResult second = new ClarifyEngine(scripted).clarify("p2");
        assertThat(second.specProduced()).isTrue();
        assertThat(second.modelAttempts()).isEqualTo(1);
    }

    /** 按调用次序回放输出的脚本模型。 */
    private static ModelPort scripted(java.util.function.Supplier<String> nextOutput) {
        return new ModelPort() {
            @Override
            public ModelReply complete(ModelRequest request) {
                return new ModelReply(nextOutput.get());
            }

            @Override
            public String name() {
                return "scripted";
            }
        };
    }

    @Test
    void invalidJsonRetriedThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        ModelPort scripted = scripted(() -> calls.incrementAndGet() < 3
                ? "这不是 JSON {{{" : VALID_SPEC);
        ClarifyEngine.ClarifyResult result = new ClarifyEngine(scripted).clarify("p");
        assertThat(result.specProduced()).isTrue();
        assertThat(result.modelAttempts()).isEqualTo(3);
    }

    @Test
    void invalidJsonBeyondLimitThrowsStableError() {
        ModelPort bad = scripted(() -> "not-json");
        assertThatThrownBy(() -> new ClarifyEngine(bad).clarify("p"))
                .isInstanceOf(ClarifyEngine.ModelOutputInvalidException.class)
                .hasMessageContaining("3 次");
    }

    @Test
    void schemaViolationRetriedWithFeedbackThenValid() {
        AtomicInteger calls = new AtomicInteger();
        ModelPort scripted = new ModelPort() {
            @Override
            public ModelReply complete(ModelRequest request) {
            if (calls.incrementAndGet() == 1) {
                // 越权 fieldType（六类白名单之外）→ Schema 违约触发重试
                return new ModelReply("""
                        {"spec":{"schemaVersion":1,"summary":"s","entities":[
                        {"name":"a_item","displayName":"A","fields":[
                        {"name":"name","displayName":"N","fieldType":"jsonblob"}]}],
                        "acceptance":["可查询"]}}
                        """);
            }
            assertThat(request.prompt()).contains("上次规格校验失败");
            return new ModelReply(VALID_SPEC);
            }

            @Override
            public String name() {
                return "scripted";
            }
        };
        ClarifyEngine.ClarifyResult result = new ClarifyEngine(scripted).clarify("p");
        assertThat(result.specProduced()).isTrue();
        assertThat(result.modelAttempts()).isEqualTo(2);
    }

    @Test
    void modelUnavailablePassesThrough() {
        ModelPort down = new ModelPort() {
            @Override
            public ModelReply complete(ModelRequest request) {
                throw new ModelUnavailableException("model_timeout", "超时");
            }

            @Override
            public String name() {
                return "down";
            }
        };
        assertThatThrownBy(() -> new ClarifyEngine(down).clarify("p"))
                .isInstanceOf(ModelUnavailableException.class);
    }

    /** P23 审查 P2-3：规格合法但简报缺失/超长 → 携修正反馈重试后成功。 */
    @Test
    void briefViolationRetriedWithFeedbackThenValid() {
        AtomicInteger calls = new AtomicInteger();
        String specOnly = VALID_SPEC.replace(
                ",\"brief\":{\"colloquial\":\"c\",\"feasibility\":\"f\",\"agentPrompt\":\"a\"}}", "}");
        ModelPort scripted = new ModelPort() {
            @Override
            public ModelReply complete(ModelRequest request) {
                if (calls.incrementAndGet() == 1) {
                    return new ModelReply(specOnly);
                }
                assertThat(request.prompt()).contains("brief");
                return new ModelReply(VALID_SPEC);
            }

            @Override
            public String name() {
                return "scripted";
            }
        };
        ClarifyEngine.ClarifyResult result = new ClarifyEngine(scripted).clarify("p");
        assertThat(result.specProduced()).isTrue();
        assertThat(result.brief()).isNotNull();
    }

    @Test
    void unknownTopLevelShapeTreatedAsInvalid() {
        List<String> outputs = List.of("{\"foo\": 1}", "{\"spec\": \"not-object\"}");
        AtomicInteger i = new AtomicInteger();
        ModelPort scripted = scripted(() -> i.incrementAndGet() <= outputs.size()
                ? outputs.get(i.get() - 1) : VALID_SPEC);
        assertThat(new ClarifyEngine(scripted).clarify("p").specProduced()).isTrue();
    }
}
