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

    /** 澄清结果：追问（未成规格）或规格草稿（未保存，由调用方落版本）。 */
    @PublicApi
    public record ClarifyResult(boolean specProduced, List<String> questions,
                                JsonNode spec, int modelAttempts, boolean outputValid) {
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
                currentPrompt = prompt + "\n\n## 上次输出非法\n你上一条回复不是合法 JSON，请只输出一个 JSON 对象。";
                continue;
            }
            if (parsed.has("questions")) {
                List<String> questions = questionsOf(parsed);
                if (questions != null) {
                    return new ClarifyResult(false, questions, null, attempts, true);
                }
                currentPrompt = prompt + "\n\n## 上次输出非法\n"
                        + "questions 必须是字符串数组，请按格式重新输出。";
                continue;
            }
            JsonNode spec = parsed.get("spec");
            if (spec == null || !spec.isObject()) {
                currentPrompt = prompt + "\n\n## 上次输出非法\n缺少 questions 或 spec 字段，请按格式重新输出。";
                continue;
            }
            List<String> errors = RequirementSchema.validate(spec);
            if (errors.isEmpty()) {
                return new ClarifyResult(true, List.of(), spec, attempts, true);
            }
            currentPrompt = prompt + "\n\n## 上次规格校验失败\n"
                    + String.join("\n", errors) + "\n请修正后重新输出完整 spec。";
        }
        throw new ModelOutputInvalidException(attempts);
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
