package com.flexforge.ai.model;

import com.flexforge.common.PublicApi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 固定 fixture 模型（docs/09 P11：模型接口未最终确认前，开发与回归只依赖
 * fixture；fixture 优先）。脚本化两轮澄清：首轮追问字段/规则/验收标准，
 * 收到回答后产出与 prompts/v3 一一对应的确定性规格草稿+三段简报（资源文件化，
 * 回归可重复）。P15 起不注册为 Bean——由 RoutingModelPort 按有效配置路由持有。
 * P28：识别知识库助手提示词（kb-assistant 模板的「知识库参考」数据段标记），
 * 从段内解析命中条目标题，产出确定性引用式回答（助手页 fixture 演示零外部依赖）。
 */
@PublicApi
public class FixtureModelPort implements ModelPort {

    private static final String ROUND_1 = "{\"questions\":["
            + "\"需要哪些字段（名称/类型/是否必填）？\","
            + "\"有哪些业务规则（如数量非负）？\","
            + "\"验收标准是什么（至少一条可验证项）？\"]}";

    /** 助手知识段/附件段标记与条目行格式（与 flexforge-kb prompts/kb/assistant-v2.md
     * 一一对应，模板改版措辞时必须同步，否则 fixture 助手退化为无命中口径）。 */
    static final String KB_SECTION_MARKER = "## 知识库参考（数据）";
    static final String KB_ATTACHMENTS_MARKER = "## 附件参考（数据）";
    static final String KB_QUESTION_MARKER = "## 用户提问（数据）";
    static final String KB_NO_HIT = "（无命中条目）";
    static final String KB_NO_ATTACHMENTS = "（无附件）";
    /** 工坊段标记（flexforge-issue prompts/workshop-v1.md 一一对应，P30）。 */
    static final String WORKSHOP_MESSAGE_MARKER = "## 用户消息（数据）";
    static final String WORKSHOP_HISTORY_MARKER = "## 对话记录（数据）";
    /** 助手业务段标记（flexforge-kb prompts/kb/assistant-v3.md 一一对应，P30）。 */
    static final String KB_ENTITY_INDEX_MARKER = "## 业务实体索引（数据）";
    static final String KB_TOOL_RESULT_MARKER = "## 工具结果（数据）";
    private static final Pattern KB_ENTITY_PAIR =
            Pattern.compile("([^((（]*)\\(([a-z0-9_]+)\\)(、|$)");
    private static final Pattern KB_ENTRY_LINE =
            Pattern.compile("^### \\[(.*)] (.+)$");
    private static final Pattern KB_ATTACHMENT_LINE =
            Pattern.compile("^### (.+)（.*）$");

    private final String round2 = loadFixtureSpec();

    @Override
    public ModelReply complete(ModelRequest request) {
        // 助手/工坊提示词优先识别（clarify 模板不含这两个段标记，路径互不干扰）
        if (request.prompt().contains(KB_SECTION_MARKER)) {
            return new ModelReply(kbAnswer(request.prompt()));
        }
        if (request.prompt().contains(WORKSHOP_MESSAGE_MARKER)) {
            return new ModelReply(workshopAnswer(request.prompt()));
        }
        // 确定性脚本：提示词带非空用户回答（第二轮）则产出规格，否则追问。
        // 轮次判定依赖 clarify.md 模板中"## 用户回答（数据）"段标记——模板改版
        // 措辞时必须同步此判定，否则 fixture 会永远停在追问轮。
        String marker = "## 用户回答（数据）";
        int idx = request.prompt().indexOf(marker);
        boolean hasAnswer = idx >= 0
                && !request.prompt().substring(idx + marker.length()).strip().equals("（无）");
        return new ModelReply(hasAnswer ? round2 : ROUND_1);
    }

    @Override
    public String name() {
        return "fixture-clarify-v3";
    }

    /** fixture 助手回答：无命中=明示暂无资料（不编造）；命中=逐条引用标题的演示回答；
     * 附件逐份确认收到（提取注入语义在 http 供应商侧，fixture 只做确定性回执）。
     * 段边界取 lastIndexOf（P28 审查 P3-2：历史注入的标记副本先于真实段出现）。
     * P30：业务实体索引段+工具结果段——业务结构类问题先回工具调用 JSON，
     * 拿到工具结果后按其内容确定性作答。 */
    static String kbAnswer(String prompt) {
        if (prompt.contains(KB_TOOL_RESULT_MARKER)) {
            return toolResultAnswer(prompt);
        }
        String toolCall = businessToolCall(prompt);
        if (toolCall != null) {
            return toolCall;
        }
        String knowledge = section(prompt, KB_SECTION_MARKER, KB_ATTACHMENTS_MARKER,
                KB_QUESTION_MARKER);
        List<String> attachments = attachmentNames(prompt);
        List<String> titles = knowledgeHitTitles(knowledge);
        if (!titles.isEmpty()) {
            return knowledgeAnswer(titles, attachments, false);
        }
        return knowledgeAnswer(List.of(), attachments, true);
    }

    /** 附件回执名单（"（无附件）"占位不算）。 */
    private static List<String> attachmentNames(String prompt) {
        String attachmentSection = section(prompt, KB_ATTACHMENTS_MARKER,
                KB_QUESTION_MARKER, KB_QUESTION_MARKER);
        List<String> attachments = new ArrayList<>();
        if (attachmentSection.contains(KB_NO_ATTACHMENTS)) {
            return attachments;
        }
        for (String line : attachmentSection.split("\n")) {
            Matcher file = KB_ATTACHMENT_LINE.matcher(line.strip());
            if (file.matches()) {
                attachments.add(file.group(1));
            }
        }
        return attachments;
    }

    /** 知识段命中条目标题（"（无命中条目）"或无条目行=空表）。 */
    private static List<String> knowledgeHitTitles(String knowledge) {
        if (knowledge.contains(KB_NO_HIT)) {
            return List.of();
        }
        List<String> titles = new ArrayList<>();
        for (String line : knowledge.split("\n")) {
            Matcher matcher = KB_ENTRY_LINE.matcher(line.strip());
            if (matcher.matches()) {
                titles.add(matcher.group(2));
            }
        }
        return titles;
    }

    private static String knowledgeAnswer(List<String> titles, List<String> attachments,
                                          boolean noHit) {
        if (!noHit) {
            StringBuilder answer = new StringBuilder("根据知识库相关条目，为你整理如下：\n");
            for (String title : titles) {
                answer.append("- 《").append(title).append("》\n");
            }
            answer.append("如需完整内容，可在知识库页查看对应条目。");
            appendAttachmentReceipt(answer, attachments);
            return answer.toString();
        }
        StringBuilder answer = new StringBuilder(
                attachments.isEmpty()
                        ? "知识库中暂无与该问题直接相关的内容，请联系管理员在知识库页补充条目后再试。"
                        : "知识库中暂无与该问题直接相关的内容。");
        appendAttachmentReceipt(answer, attachments);
        return answer.toString();
    }

    private static void appendAttachmentReceipt(StringBuilder answer, List<String> attachments) {
        if (!attachments.isEmpty()) {
            answer.append("\n已收到附件：");
            answer.append(String.join("、", attachments));
            answer.append("。");
        }
    }

    /** fixture 工坊脚本（P30）：对话记录里没有用户发言→追问三件事；有→从当前
     * 用户消息确定性派生 title/description 输出 create_issue 工具调用。
     * 段边界取标记定位（历史与当前消息分节，消息内嵌 "用户：" 字面量不干扰）。 */
    static String workshopAnswer(String prompt) {
        int historyStart = prompt.lastIndexOf(WORKSHOP_HISTORY_MARKER);
        int messageStart = prompt.lastIndexOf(WORKSHOP_MESSAGE_MARKER);
        String history = historyStart >= 0 && messageStart > historyStart
                ? prompt.substring(historyStart + WORKSHOP_HISTORY_MARKER.length(), messageStart)
                : "";
        if (!history.contains("用户：")) {
            return "{\"reply\":\"好的，我们先明确三件事：\\n"
                    + "1. 需要管理什么数据，有哪些字段（名称/类型/是否必填）？\\n"
                    + "2. 有什么业务规则（如数量非负）？\\n"
                    + "3. 验收标准是什么（至少一条可验证项）？\"}";
        }
        String message = messageStart >= 0
                ? prompt.substring(messageStart + WORKSHOP_MESSAGE_MARKER.length()).strip() : "";
        int sectionBreak = message.indexOf("\n## ");
        if (sectionBreak >= 0) {
            message = message.substring(0, sectionBreak).strip();
        }
        String title = message.length() > 20 ? message.substring(0, 20) : message;
        String description = message.length() > 400
                ? message.substring(0, 400) : message;
        return "{\"tool\":\"create_issue\",\"title\":" + quote(title)
                + ",\"description\":" + quote(description) + "}";
    }

    /** JSON 字符串转义（fixture 内构造少量字段的确定性输出）；
     * 其余控制字符统一转 unicode 转义序列（审查 P3-3：U+000B 等会产出非法 JSON）。 */
    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** 业务工具判定：提问命中索引中的实体（显示名或 name）→ inspect_entity；
     * 问"有哪些业务"且索引非空 → list_entities；否则 null（走普通回答路径）。
     * 段定位取 lastIndexOf（审查 P3-2：与 kb 主路径同口径，用户消息注入的
     * 标记字面量先于真实段出现时不误导）。 */
    static String businessToolCall(String prompt) {
        int indexStart = prompt.lastIndexOf(KB_ENTITY_INDEX_MARKER);
        if (indexStart < 0) {
            return null;
        }
        String index = sectionAfter(prompt, indexStart + KB_ENTITY_INDEX_MARKER.length());
        if (index.contains("（暂无业务实体）")) {
            return null;
        }
        String question = questionSection(prompt);
        String inspect = inspectCallFor(index, question);
        return inspect != null ? inspect : listCallFor(question);
    }

    /** 提问命中索引实体 → inspect_entity 工具调用 JSON（未命中 null）。
     * 按顿号分段、取段尾 (name) 为实体名（审查 P3-4：displayName 含括号段
     * 时正则跨段误配，如 采购订单(v2)(purchase_order) 的 v2 被当实体名）。 */
    private static String inspectCallFor(String index, String question) {
        for (String raw : index.split("、")) {
            String segment = raw.strip();
            String entityName = entityNameOf(segment);
            if (entityName == null) {
                continue;
            }
            if (questionMentions(displayOf(segment, entityName), entityName, question)) {
                return "{\"tool\":\"inspect_entity\",\"entity\":" + quote(entityName) + "}";
            }
        }
        return null;
    }

    /** 命中判定：显示名（或其首个括号段前缀，如 采购订单(v2) → 采购订单）
     * 或实体名出现在提问中。 */
    private static boolean questionMentions(String display, String entityName,
                                            String question) {
        if (display.isEmpty()) {
            return question.contains(entityName);
        }
        int paren = display.indexOf('(');
        String prefix = paren > 0 ? display.substring(0, paren) : display;
        return question.contains(display) || question.contains(entityName)
                || (!prefix.isEmpty() && question.contains(prefix));
    }

    private static String listCallFor(String question) {
        if (question.contains("业务") && (question.contains("哪些") || question.contains("什么"))) {
            return "{\"tool\":\"list_entities\"}";
        }
        return null;
    }

    private static String displayOf(String segment, String entityName) {
        return segment.endsWith("(" + entityName + ")")
                ? segment.substring(0, segment.length() - entityName.length() - 2) : segment;
    }

    /** 段尾半角括号内的实体名（采购订单(v2)(purchase_order) → purchase_order）。 */
    private static String entityNameOf(String segment) {
        if (!segment.endsWith(")")) {
            return null;
        }
        int open = segment.lastIndexOf('(');
        if (open < 0) {
            return null;
        }
        String name = segment.substring(open + 1, segment.length() - 1);
        return name.matches("[a-z0-9_]+") ? name : null;
    }

    /** 工具结果作答：保留正文行（跳过 [tool] 结果头行），注明来源为平台业务结构
     * ——inspect 形态（业务：/字段行）与 list 形态（索引行）都覆盖（审查 P2-6：
     * 原全角括号条件吞掉了 list 结果全部行）。 */
    static String toolResultAnswer(String prompt) {
        int start = prompt.lastIndexOf(KB_TOOL_RESULT_MARKER) + KB_TOOL_RESULT_MARKER.length();
        String result = sectionAfter(prompt, start);
        StringBuilder answer = new StringBuilder("根据平台业务结构，为你整理如下：\n");
        for (String line : result.split("\n")) {
            String stripped = line.strip();
            if (!stripped.isEmpty() && !stripped.startsWith("[")) {
                answer.append(stripped).append('\n');
            }
        }
        answer.append("以上信息来自业务实体元数据。");
        return answer.toString();
    }

    /** 截取标记之后到下一个 "## " 段或文末的文本。 */
    private static String sectionAfter(String prompt, int from) {
        int end = prompt.indexOf("\n## ", from);
        return end > from ? prompt.substring(from, end) : prompt.substring(from);
    }

    private static String questionSection(String prompt) {
        int start = prompt.lastIndexOf(KB_QUESTION_MARKER);
        return start < 0 ? "" : sectionAfter(prompt, start + KB_QUESTION_MARKER.length());
    }

    /** 截取 [startMarker, endMarker) 数据段（end 取 lastIndexOf 防历史注入副本；
     * 段缺失时依次回退后续 end 候选，全部缺失截到文末）。 */
    private static String section(String prompt, String startMarker, String primaryEnd,
                                  String fallbackEnd) {
        int start = prompt.indexOf(startMarker);
        if (start < 0) {
            return "";
        }
        int from = start + startMarker.length();
        int end = prompt.lastIndexOf(primaryEnd);
        if (end <= from) {
            end = prompt.lastIndexOf(fallbackEnd);
        }
        return end > from ? prompt.substring(from, end) : prompt.substring(from);
    }

    private static String loadFixtureSpec() {
        try (InputStream in = FixtureModelPort.class.getResourceAsStream(
                "/prompts/v3/fixture-spec.json")) {
            if (in == null) {
                throw new IllegalStateException("fixture 规格资源缺失: prompts/v3/fixture-spec.json");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("fixture 规格资源读取失败", e);
        }
    }
}
