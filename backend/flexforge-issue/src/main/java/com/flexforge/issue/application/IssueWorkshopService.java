package com.flexforge.issue.application;

import com.flexforge.ai.model.ModelPort;
import com.flexforge.common.PublicApi;
import com.flexforge.common.audit.AuditEventPort;
import com.flexforge.common.audit.AuditEvents;
import com.flexforge.issue.domain.IssueWorkshopRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 需求工坊编排（FR-ISSUE-09，docs/13 §3.6-9）：工坊对话（按用户隔离）→
 * workshop-v1 双态 JSON → 追问直接回复 / create_issue 工具执行（Spring 注入
 * 的 WorkshopTool 白名单）→ 创建后以对话摘要跑既有 clarify（IssueAiService，
 * 任务记录与审计复用）落规格草稿+三段简报，失败降级不回滚创建。
 * 模型失败/非法输出（重试一次后）不落半截会话。
 */
@PublicApi
@Service
public class IssueWorkshopService {

    /** 工坊预算（docs/09 P30：与助手会话同口径）。 */
    public static final int MESSAGE_MAX = 2000;
    public static final int HISTORY_MESSAGES = 8;
    public static final int HISTORY_MAX_CHARS = 4000;
    public static final int DIGEST_MAX_CHARS = 3000;
    public static final int HISTORY_LOAD_LIMIT = 100;
    /** 模型回复落库上限（V021 CHECK <=8000；审查 P2-4，与 kb ANSWER_STORE_MAX 同口径）。 */
    public static final int REPLY_STORE_MAX = 8000;
    private static final int MAX_ATTEMPTS = 2;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 工坊发言结果：reply 进对话；issueId/issueTitle 非空=本轮已创建需求。 */
    @PublicApi
    public record WorkshopOutcome(String reply, String issueId, String issueTitle) {
    }

    /** 模型输出的双态解析结果（互斥）。 */
    private record Parsed(String reply, String toolName, Map<String, String> args, String error) {

        static Parsed invalid(String error) {
            return new Parsed(null, null, null, error);
        }
    }

    /** 工坊协作者内核（参数上限口径，对齐 IssueAiService.AiKernel 模式）。 */
    @PublicApi
    record WorkshopKernel(IssueWorkshopRepository workshop, IssueAiService issueAi,
                          ModelPort model, AuditEventPort audit, Clock clock) {
    }

    private final WorkshopKernel kernel;
    private final Map<String, WorkshopTool> tools = new HashMap<>();

    public IssueWorkshopService(WorkshopKernel kernel, List<WorkshopTool> toolBeans) {
        this.kernel = kernel;
        for (WorkshopTool tool : toolBeans) {
            tools.put(tool.name(), tool);
        }
    }

    public List<IssueWorkshopRepository.WorkshopMessageRecord> messagesOf(String userId) {
        return kernel.workshop().recentOf(userId, HISTORY_LOAD_LIMIT);
    }

    public int clear(String operator) {
        int removed = kernel.workshop().deleteAllOf(operator);
        kernel.audit().record(AuditEvents.of(operator, "issue.workshop.clear", operator,
                "cleared", kernel.clock()));
        return removed;
    }

    /** 工坊发言：追问或工具创建；模型失败（含不可用）零写入上抛。 */
    public WorkshopOutcome send(String operator, String message) {
        String trimmed = requireMessage(message);
        String prompt = WorkshopPromptTemplates.render(Map.of(
                "history", historyText(operator), "message", trimmed));
        try {
            return complete(operator, trimmed, prompt);
        } catch (RuntimeException e) {
            kernel.audit().record(AuditEvents.of(operator, "issue.workshop", operator,
                    "failure", kernel.clock()));
            throw e;
        }
    }

    private WorkshopOutcome complete(String operator, String message, String prompt) {
        String current = prompt;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Parsed parsed = parse(kernel.model().complete(new ModelPort.ModelRequest(
                    WorkshopPromptTemplates.VERSION, current)).text());
            if (parsed.reply() != null) {
                persist(operator, message, capReply(parsed.reply()), null);
                return new WorkshopOutcome(parsed.reply(), null, null);
            }
            if (parsed.toolName() != null) {
                WorkshopTool tool = tools.get(parsed.toolName());
                if (tool == null) {
                    current = feedback(prompt, "未知工具: " + parsed.toolName()
                            + "，只允许 create_issue。");
                    continue;
                }
                try {
                    WorkshopTool.ToolResult result = tool.execute(operator, parsed.args());
                    return persistCreated(operator, message, result);
                } catch (IllegalArgumentException e) {
                    current = feedback(prompt, "工具参数未通过校验: " + e.getMessage()
                            + "，请修正 title/description 后重新输出工具调用。");
                }
                continue;
            }
            current = feedback(prompt, parsed.error());
        }
        throw new IllegalArgumentException("模型输出经 " + MAX_ATTEMPTS + " 次校验仍不合法（追问/工具调用二选一的 JSON），请重试");
    }

    /** 创建成功后的落地与后续 clarify（摘要→规格草稿+三段简报；失败降级）。 */
    private WorkshopOutcome persistCreated(String operator, String message,
                                           WorkshopTool.ToolResult result) {
        String digest = (historyText(operator) + "\n用户：" + message).strip();
        digest = digest.length() > DIGEST_MAX_CHARS
                ? digest.substring(digest.length() - DIGEST_MAX_CHARS) : digest;
        String tail;
        try {
            IssueAiService.ClarifyOutcome clarify = kernel.issueAi().clarify(
                    operator, result.issueId(), digest);
            tail = clarify.specProduced()
                    ? "规格草稿与三段简报已生成，请在下方卡片确认推送。"
                    : "还有细节需要确认：" + String.join("；", clarify.questions())
                            + "（可从右侧需求列表进入对话继续）。";
        } catch (RuntimeException e) {
            tail = "规格生成未完成，可从右侧需求列表进入对话重试。";
        }
        String createdReply = capReply("已创建需求《" + result.title() + "》。" + tail);
        persist(operator, message, createdReply, result.issueId());
        return new WorkshopOutcome(createdReply, result.issueId(), result.title());
    }

    private void persist(String operator, String message, String reply, String issueId) {
        kernel.workshop().insertExchange(
                new IssueWorkshopRepository.WorkshopMessageRecord(
                        "iwm-" + UUID.randomUUID(), operator, "user", message, null),
                new IssueWorkshopRepository.WorkshopMessageRecord(
                        "iwm-" + UUID.randomUUID(), operator, "assistant", reply, issueId));
        kernel.audit().record(AuditEvents.of(operator, "issue.workshop", operator, "success", kernel.clock()));
    }

    /** 双态解析：{"reply"} 或 {"tool","title","description"}；非法给出反馈理由。 */
    private static Parsed parse(String text) {
        JsonNode node;
        try {
            node = JSON.readTree(text.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return Parsed.invalid("你上一条回复不是合法 JSON，请只输出一个 JSON 对象。");
        }
        if (node == null || !node.isObject()) {
            return Parsed.invalid("请只输出一个 JSON 对象。");
        }
        Parsed reply = parseReply(node);
        if (reply != null) {
            return reply;
        }
        return parseToolCall(node);
    }

    private static Parsed parseReply(JsonNode node) {
        JsonNode reply = node.get("reply");
        if (reply == null) {
            return null;
        }
        if (!reply.isTextual() || reply.asString().isBlank()) {
            return Parsed.invalid("reply 必须是非空文本。");
        }
        return new Parsed(reply.asString(), null, null, null);
    }

    private static Parsed parseToolCall(JsonNode node) {
        JsonNode tool = node.get("tool");
        if (tool == null || !tool.isTextual()) {
            return Parsed.invalid("缺少 reply 或 tool 字段。");
        }
        String title = textArg(node, WorkshopTool.TITLE_ARG);
        String description = textArg(node, WorkshopTool.DESCRIPTION_ARG);
        if (title == null || description == null) {
            return Parsed.invalid("create_issue 需要 title 与 description 非空文本参数。");
        }
        Map<String, String> args = new HashMap<>();
        args.put(WorkshopTool.TITLE_ARG, title);
        args.put(WorkshopTool.DESCRIPTION_ARG, description);
        return new Parsed(null, tool.asString(), Map.copyOf(args), null);
    }

    private static String textArg(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asString().isBlank()
                ? value.asString() : null;
    }

    private static String feedback(String prompt, String message) {
        return prompt + "\n\n## 上次输出问题\n" + message + "\n请重新输出符合契约的一个 JSON 对象。";
    }

    /** 码点安全截断（审查 P2-4/P3-10：substring 劈开代理对会产非法半字符）。 */
    static String truncateFromTail(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        int start = text.length() - maxChars;
        if (Character.isLowSurrogate(text.charAt(start))) {
            start++;
        }
        return text.substring(start);
    }

    static String capReply(String reply) {
        if (reply.length() <= REPLY_STORE_MAX) {
            return reply;
        }
        int end = REPLY_STORE_MAX;
        if (Character.isHighSurrogate(reply.charAt(end - 1))) {
            end--;
        }
        return reply.substring(0, end);
    }

    private static String requireMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("消息不能为空");
        }
        String trimmed = message.strip();
        if (trimmed.length() > MESSAGE_MAX) {
            throw new IllegalArgumentException("消息超过 " + MESSAGE_MAX + " 字符上限");
        }
        return trimmed;
    }

    private String historyText(String userId) {
        List<IssueWorkshopRepository.WorkshopMessageRecord> recent =
                kernel.workshop().recentOf(userId, HISTORY_MESSAGES);
        if (recent.isEmpty()) {
            return "（无）";
        }
        StringBuilder text = new StringBuilder();
        for (IssueWorkshopRepository.WorkshopMessageRecord message : recent) {
            text.append("user".equals(message.role()) ? "用户：" : "助手：")
                    .append(message.content()).append('\n');
        }
        String joined = text.toString();
        if (joined.length() <= HISTORY_MAX_CHARS) {
            return joined;
        }
        String clipped = joined.substring(joined.length() - HISTORY_MAX_CHARS);
        int firstLine = clipped.indexOf('\n');
        return firstLine >= 0 && firstLine + 1 < clipped.length()
                ? clipped.substring(firstLine + 1) : clipped;
    }
}
