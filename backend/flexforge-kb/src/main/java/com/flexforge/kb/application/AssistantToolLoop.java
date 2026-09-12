package com.flexforge.kb.application;

import com.flexforge.ai.model.ModelPort;
import com.flexforge.ai.model.ModelUnavailableException;
import com.flexforge.common.PublicApi;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 助手工具环路（FR-KB-07，P30）：普通回答直接返回；模型输出 {"tool":...}
 * JSON 时执行工具并把结果以「工具结果」数据段追加后重新调用模型（工具调用
 * 上限 MAX_TOOL_CALLS，模型调用总额度上限 MAX_ROUNDS，超限按非法输出上抛）。
 * 工具/参数错误以数据段回灌（不中断对话），空回复按不可用上抛（同 P28 口径）。
 */
@PublicApi
final class AssistantToolLoop {

    static final int MAX_TOOL_CALLS = 2;
    private static final int MAX_ROUNDS = 4;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TOOL_RESULT_MARKER = "## 工具结果（数据）";

    private AssistantToolLoop() {
    }

    record Loop(ModelPort model, Map<String, AssistantTool> tools) {
    }

    static String answer(Loop loop, String prompt) {
        String current = prompt;
        StringBuilder results = new StringBuilder();
        int calls = 0;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            String text = complete(loop.model(), current);
            ToolCall call = toolCallOf(text);
            if (call == null) {
                return text;
            }
            if (calls >= MAX_TOOL_CALLS) {
                current = prompt + "\n\n" + TOOL_RESULT_MARKER + "\n" + results
                        + "\n（工具调用额度已用完，请基于以上信息直接回答，不要输出 JSON）";
                continue;
            }
            calls++;
            results.append("[").append(call.name()).append("]\n")
                    .append(run(loop, call)).append("\n\n");
            current = prompt + "\n\n" + TOOL_RESULT_MARKER + "\n" + results;
        }
        throw new IllegalArgumentException("模型未能基于工具结果给出最终回答，请重试");
    }

    private static String complete(ModelPort model, String prompt) {
        String text = model.complete(new ModelPort.ModelRequest(
                KbPromptTemplates.VERSION, prompt)).text().strip();
        if (text.isEmpty()) {
            throw new ModelUnavailableException(
                    ModelUnavailableException.REASON_OFFLINE, "模型返回空回复");
        }
        return text;
    }

    private static String run(Loop loop, ToolCall call) {
        AssistantTool tool = loop.tools().get(call.name());
        if (tool == null) {
            return "错误：未知工具 " + call.name();
        }
        try {
            return tool.apply(call.parameters());
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        }
    }

    record ToolCall(String name, Map<String, String> parameters) {
    }

    /** 识别工具调用输出：整体为 JSON 对象且含文本 tool 字段（其余文本字段作参数）；
     * 非该形态（普通回答文本）返回 null。 */
    static ToolCall toolCallOf(String text) {
        String trimmed = text.strip();
        if (!trimmed.startsWith("{")) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(trimmed.getBytes(StandardCharsets.UTF_8));
            if (!node.isObject()) {
                return null;
            }
            JsonNode name = node.get("tool");
            if (name == null || !name.isTextual()) {
                return null;
            }
            Map<String, String> parameters = new HashMap<>();
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                if (!"tool".equals(entry.getKey()) && entry.getValue().isTextual()) {
                    parameters.put(entry.getKey(), entry.getValue().asString());
                }
            }
            return new ToolCall(name.asString(), Map.copyOf(parameters));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
