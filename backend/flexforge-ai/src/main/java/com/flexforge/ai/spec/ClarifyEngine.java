package com.flexforge.ai.spec;

import com.flexforge.ai.model.ModelPort;
import com.flexforge.ai.model.ModelUnavailableException;
import com.flexforge.common.PublicApi;
import com.flexforge.common.api.ErrorCodes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 澄清引擎（docs/09 P11、FR-ISSUE-03/04）：模板化提示词 → 模型端口 →
 * JSON 解析 → RequirementSchema 校验 → 有限重试（上限 2 次追加校验错误
 * 反馈）；超限抛稳定错误码 model_output_invalid。纯编排，不落库不审计。
 */
@PublicApi
public class ClarifyEngine {

    /** 校验失败重试上限（含首次共 3 次模型调用）。 */
    public static final int MAX_ATTEMPTS = 3;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 澄清结果：追问（未成规格）或规格草稿+三段简报（未保存，由调用方落版本）。
     * brief 为 v3 起规格同轮产出的 {colloquial, feasibility, agentPrompt}。 */
    @PublicApi
    public record ClarifyResult(boolean specProduced, List<String> questions,
                                JsonNode spec, JsonNode brief,
                                int modelAttempts, boolean outputValid) {
    }

    private final ModelPort model;

    public ClarifyEngine(ModelPort model) {
        this.model = model;
    }

    public ClarifyResult clarify(String prompt) {
        String currentPrompt = prompt;
        int attempts = 0;
        // 反馈不跨轮累积：每次都从原始提示词 + 最近一次错误重建，防止重试对话
        // 滚雪球撑爆上下文（错误清单已随版本留存，无需在提示词里留全史）
        while (attempts < MAX_ATTEMPTS) {
            attempts++;
            JsonNode parsed = parse(model.complete(new ModelPort.ModelRequest(
                    PromptTemplates.VERSION, currentPrompt)).text());
            if (parsed == null) {
                currentPrompt = retryIllegal(prompt, "你上一条回复不是合法 JSON，请只输出一个 JSON 对象。");
                continue;
            }
            if (parsed.has("questions")) {
                List<String> questions = questionsOf(parsed);
                if (questions != null) {
                    return new ClarifyResult(false, questions, null, null, attempts, true);
                }
                currentPrompt = retryIllegal(prompt, "questions 必须是字符串数组，请按格式重新输出。");
                continue;
            }
            JsonNode spec = parsed.get("spec");
            if (spec == null || !spec.isObject()) {
                currentPrompt = retryIllegal(prompt, "缺少 questions 或 spec 字段，请按格式重新输出。");
                continue;
            }
            String briefError = briefErrorOf(parsed.get("brief"));
            if (briefError != null) {
                currentPrompt = retryIllegal(prompt, briefError + "\n请修正后重新输出完整 spec 与 brief。");
                continue;
            }
            List<String> errors = RequirementSchema.validate(spec);
            if (errors.isEmpty()) {
                return new ClarifyResult(true, List.of(), spec, parsed.get("brief"),
                        attempts, true);
            }
            currentPrompt = retrySpecInvalid(prompt, errors);
        }
        throw new ModelOutputInvalidException(attempts);
    }

    /** 非法输出的重试反馈（从原始提示词重建，反馈只含最近一次错误）。 */
    private static String retryIllegal(String prompt, String message) {
        return prompt + "\n\n## 上次输出非法\n" + message;
    }

    /** 规格校验失败的重试反馈。 */
    private static String retrySpecInvalid(String prompt, List<String> errors) {
        return prompt + "\n\n## 上次规格校验失败\n" + String.join("\n", errors)
                + "\n请修正后重新输出完整 spec 与 brief。";
    }

    /** 三段简报校验（v3）：brief 必须是对象且三段均为非空文本 ≤4000 字符；
     * 返回 null=合法，否则返回给模型的修正反馈。 */
    private static final List<String> BRIEF_FIELDS = List.of(
            "colloquial", "feasibility", "agentPrompt");
    private static final int BRIEF_MAX_CHARS = 4000;

    static String briefErrorOf(JsonNode brief) {
        if (brief == null || !brief.isObject()) {
            return "产出规格时必须同时给出 brief 对象（colloquial/feasibility/agentPrompt）。";
        }
        for (String field : BRIEF_FIELDS) {
            JsonNode value = brief.get(field);
            if (value == null || !value.isTextual() || value.asString().isBlank()) {
                return "brief." + field + " 必须是非空文本。";
            }
            if (value.asString().length() > BRIEF_MAX_CHARS) {
                return "brief." + field + " 超过 " + BRIEF_MAX_CHARS + " 字符上限。";
            }
        }
        return null;
    }

    /** questions 元素必须全为文本；否则视为非法输出（交由上层重试口径）。 */
    private static List<String> questionsOf(JsonNode parsed) {
        JsonNode array = parsed.get("questions");
        if (array == null || !array.isArray()) {
            return null;
        }
        java.util.List<String> questions = new java.util.ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            if (!array.get(i).isTextual()) {
                return null;
            }
            questions.add(array.get(i).asString());
        }
        return List.copyOf(questions);
    }

    private static JsonNode parse(String text) {
        try {
            return JSON.readTree(text.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 输出重试超限（RB-AI 非法 JSON 负例的稳定错误码）。 */
    @PublicApi
    public static class ModelOutputInvalidException extends RuntimeException {

        public final int attempts;

        public ModelOutputInvalidException(int attempts) {
            super("模型输出经 " + attempts + " 次校验仍不合法（非法 JSON/Schema 违约），请手工编辑规格");
            this.attempts = attempts;
        }

        public String code() {
            return ErrorCodes.MODEL_OUTPUT_INVALID;
        }
    }
}
