package com.flexforge.kb.application;

import com.flexforge.ai.model.ModelPort;
import com.flexforge.ai.model.ModelUnavailableException;
import com.flexforge.common.PublicApi;
import com.flexforge.common.audit.AuditEventPort;
import com.flexforge.common.audit.AuditEvents;
import com.flexforge.kb.domain.KbAttachments;
import com.flexforge.kb.domain.KbChatRepository;
import com.flexforge.kb.domain.KbEntryRepository;
import com.flexforge.kb.domain.KbRetrieval;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI 助手编排（FR-KB-02..05，docs/13 §3.6-7/8）：检索 Top-K + 附件提取 →
 * 模板 kb-assistant-v2 渲染 → ModelPort（http/fixture 路由唯一通道）→ 用户与
 * 助手消息及附件同事务落库（模型失败不落半截会话）。会话按 user_id 隔离，
 * 调用方传认证主体标识，不接受客户端指定他人会话。
 */
@PublicApi
@Service
public class KbAssistantService {

    /** 提问与注入预算（FR-KB-02/03/05、docs/13 §3.6-7/8 硬上限）。 */
    public static final int QUESTION_MAX = 2000;
    public static final int HISTORY_MESSAGES = 8;
    public static final int HISTORY_MAX_CHARS = 4000;
    public static final int ENTRY_EXCERPT_MAX = 1500;
    public static final int KNOWLEDGE_MAX_CHARS = 6000;
    public static final int ANSWER_STORE_MAX = 8000;
    public static final int ATTACHMENTS_MAX_CHARS = 20_000;
    /** 会话查询上限（UI 回放用）。 */
    public static final int HISTORY_LOAD_LIMIT = 100;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 上传附件入参（控制器从 multipart 构造）。 */
    @PublicApi
    public record IncomingAttachment(String filename, String contentType, byte[] data) {
    }

    /** 校验+提取后的附件（提示词渲染与落库共用；包私有供同包测试构造）。 */
    record Prepared(String filename, String contentType, long sizeBytes, byte[] data,
                    String extractedText) {
    }

    /** 助手回答与引用条目/附件回执（references 为 [{id,title,category}] 快照）。 */
    @PublicApi
    public record AskOutcome(String answer, List<Reference> references,
                             List<KbChatRepository.KbAttachmentView> attachments) {

        public AskOutcome(String answer, List<Reference> references) {
            this(answer, references, List.of());
        }
    }

    @PublicApi
    public record Reference(String id, String title, String category) {
    }

    /** 助手协作者内核（参数上限口径，P30 增工具注册表后收敛）。 */
    @PublicApi
    record KbKernel(KbEntryRepository entries, KbChatRepository chat, ModelPort model,
                    AuditEventPort audit, Clock clock) {
    }

    private final KbKernel kernel;
    private final Map<String, AssistantTool> tools = new java.util.HashMap<>();
    private final BusinessEntityTools business;

    public KbAssistantService(KbKernel kernel, List<AssistantTool> toolBeans,
                              BusinessEntityTools business) {
        this.kernel = kernel;
        this.business = business;
        for (AssistantTool tool : toolBeans) {
            tools.put(tool.name(), tool);
        }
    }

    /** 某用户会话消息（升序，最近 HISTORY_LOAD_LIMIT 条）。 */
    public List<KbChatRepository.KbMessageRecord> messagesOf(String userId) {
        return kernel.chat().recentOf(userId, HISTORY_LOAD_LIMIT);
    }

    /** 消息附件视图（会话回放；不含字节载荷）。 */
    public List<KbChatRepository.KbAttachmentView> attachmentsOf(List<String> messageIds) {
        return kernel.chat().attachmentsOf(messageIds);
    }

    /** 附件下载载荷（本人校验在调用方比对 ownerId）。 */
    public KbChatRepository.OwnedAttachment findOwnedAttachment(String attachmentId) {
        return kernel.chat().findOwned(attachmentId);
    }

    /** 清空本人会话（返回删除条数；附件经 FK 级联清理）。 */
    public int clear(String operator) {
        int removed = kernel.chat().deleteAllOf(operator);
        kernel.audit().record(AuditEvents.of(operator, "kb.clear", operator,
                "cleared", kernel.clock()));
        return removed;
    }

    /** 提问（无附件路径，P28 契约兼容）。 */
    public AskOutcome ask(String operator, String question) {
        return ask(operator, question, List.of());
    }

    /** 提问：附件先整体校验再提取；模型失败（含不可用/空回复）原样上抛，
     * 不产生任何会话写入（FR-KB-04/05）。业务结构类问题经工具环路取数
     * （FR-KB-07：工具结果只作数据段回灌，上限 MAX_TOOL_CALLS 次）。 */
    public AskOutcome ask(String operator, String question, List<IncomingAttachment> files) {
        String trimmed = requireQuestion(question);
        List<Prepared> prepared = prepare(files);
        List<KbEntryRepository.KbEntryRecord> matched =
                KbRetrieval.topMatches(trimmed, kernel.entries().listAll());
        List<Reference> references = matched.stream()
                .map(e -> new Reference(e.id(), e.title(), e.category())).toList();
        String prompt = KbPromptTemplates.render(promptParams(operator, matched, trimmed, attachmentsText(prepared)));
        try {
            String answer = AssistantToolLoop.answer(new AssistantToolLoop.Loop(
                    kernel.model(), tools), prompt);
            String stored = answer.length() > ANSWER_STORE_MAX
                    ? truncateAtCodePoint(answer, ANSWER_STORE_MAX) : answer;
            String userMessageId = "kcm-" + UUID.randomUUID();
            List<KbChatRepository.KbAttachmentRecord> rows = attachmentRows(
                    prepared, userMessageId);
            List<KbChatRepository.KbAttachmentView> receipt = rows.stream()
                    .map(r -> new KbChatRepository.KbAttachmentView(
                            r.id(), r.messageId(), r.filename(), r.contentType(), r.sizeBytes()))
                    .toList();
            persistExchange(
                    new KbChatRepository.KbMessageRecord(
                            userMessageId, operator, "user", trimmed, null),
                    new KbChatRepository.KbMessageRecord(
                            "kcm-" + UUID.randomUUID(), operator, "assistant", stored,
                            referencesJson(references)),
                    rows);
            kernel.audit().record(AuditEvents.of(operator, "kb.ask", operator, "success",
                    kernel.clock()));
            return new AskOutcome(stored, references, receipt);
        } catch (RuntimeException e) {
            kernel.audit().record(AuditEvents.of(operator, "kb.ask", operator, "failure",
                    kernel.clock()));
            throw e;
        }
    }

    /** 附件白名单/数量/尺寸整体校验 + 文本提取（图片与失败=仅存档）；
     * 白名单按原始（清洗后未截断）文件名判定（审查 P3-1：截断丢扩展名会误拒），
     * 落库名压缩到 255 且保留扩展名段。 */
    private static List<Prepared> prepare(List<IncomingAttachment> files) {
        if (files == null) {
            return List.of();
        }
        if (files.size() > KbAttachments.MAX_FILES) {
            throw new IllegalArgumentException("单次最多 " + KbAttachments.MAX_FILES + " 个附件");
        }
        List<Prepared> prepared = new ArrayList<>();
        for (IncomingAttachment file : files) {
            String sanitized = sanitizeFilename(file.filename());
            KbAttachments.validate(sanitized, file.data() == null ? 0 : file.data().length);
            String filename = shrinkFilename(sanitized);
            prepared.add(new Prepared(filename, capLength(
                    file.contentType() == null ? "application/octet-stream" : file.contentType(),
                    255), file.data().length, file.data(),
                    KbAttachments.extract(filename, file.data())));
        }
        return List.copyOf(prepared);
    }

    private static String capLength(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    /** 超长文件名压缩到 ≤255 且保留扩展名（截前段留尾段扩展）。 */
    static String shrinkFilename(String name) {
        if (name.length() <= 255) {
            return name;
        }
        String ext = KbAttachments.extensionOf(name);
        int keep = ext.isEmpty() ? 254 : 255 - ext.length() - 1;
        return name.substring(0, keep) + (ext.isEmpty() ? "" : "." + ext);
    }

    /** 剥离客户端路径成分（basename）与控制字符（审查 P3-2：CR/LF 可污染
     * Content-Disposition 与提示词数据段），空名兜底 "attachment"。 */
    static String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "attachment";
        }
        String name = filename.replaceAll("\\p{Cntrl}", "");
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        name = slash >= 0 ? name.substring(slash + 1) : name;
        return name.isBlank() ? "attachment" : name;
    }

    private static String requireQuestion(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        String trimmed = question.strip();
        if (trimmed.length() > QUESTION_MAX) {
            throw new IllegalArgumentException("问题超过 " + QUESTION_MAX + " 字符上限");
        }
        return trimmed;
    }

    /** 提示词参数：LinkedHashMap 固定替换顺序（P28 审查 P3-1）。 */
    private Map<String, String> promptParams(String userId,
                                             List<KbEntryRepository.KbEntryRecord> matched,
                                             String question, String attachments) {
        Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("history", historyText(userId));
        params.put("knowledge", knowledgeText(matched));
        params.put("attachments", attachments);
        params.put("entities", business.entityIndex());
        params.put("question", question);
        return params;
    }

    private void persistExchange(KbChatRepository.KbMessageRecord userMessage,
                                 KbChatRepository.KbMessageRecord assistantMessage,
                                 List<KbChatRepository.KbAttachmentRecord> rows) {
        kernel.chat().insertExchange(userMessage, assistantMessage, rows);
    }

    private static List<KbChatRepository.KbAttachmentRecord> attachmentRows(
            List<Prepared> prepared, String messageId) {
        return prepared.stream()
                .map(p -> new KbChatRepository.KbAttachmentRecord(
                        "kba-" + UUID.randomUUID(), messageId, p.filename(),
                        p.contentType(), p.sizeBytes(), p.data(), p.extractedText()))
                .toList();
    }

    /** 附件数据段：每文件标题行（图片/未提取标注）+提取文本；首个附件无条件
     * 保留（正文按剩余预算截断），后续超预算标注省略（审查 P2-1：原实现
     * 首块即超限时静默丢弃并谎报"（无附件）"）。 */
    static String attachmentsText(List<Prepared> prepared) {
        if (prepared == null || prepared.isEmpty()) {
            return "（无附件）";
        }
        StringBuilder text = new StringBuilder();
        boolean omitted = false;
        for (Prepared p : prepared) {
            String ext = KbAttachments.extensionOf(p.filename());
            String note = KbAttachments.isImage(p.filename()) ? "，图片未提取文本"
                    : p.extractedText() == null ? "，未提取到文本" : "";
            String header = "### " + p.filename() + "（" + ext + note + "）\n";
            if (text.length() + header.length() + 2 > ATTACHMENTS_MAX_CHARS) {
                omitted = true;
                break;
            }
            int remaining = ATTACHMENTS_MAX_CHARS - text.length()
                    - header.length() - "\n\n".length();
            String body = p.extractedText() == null ? ""
                    : truncateAtCodePoint(p.extractedText(), Math.max(0, remaining));
            text.append(header).append(body).append("\n\n");
        }
        if (omitted) {
            text.append("（其余附件内容过长已省略）\n");
        }
        return text.isEmpty() ? "（无附件）" : text.toString().strip();
    }

    /** 会话历史数据段：近 HISTORY_MESSAGES 条，超 HISTORY_MAX_CHARS 从最旧行裁剪。 */
    private String historyText(String userId) {
        List<KbChatRepository.KbMessageRecord> recent =
                kernel.chat().recentOf(userId, HISTORY_MESSAGES);
        if (recent.isEmpty()) {
            return "（无）";
        }
        StringBuilder text = new StringBuilder();
        for (KbChatRepository.KbMessageRecord message : recent) {
            text.append("user".equals(message.role()) ? "用户：" : "助手：")
                    .append(message.content()).append('\n');
        }
        return clipOldestLines(text.toString(), HISTORY_MAX_CHARS);
    }

    /** 知识库数据段：Top-K 条目按分值序嵌入；总长超限停止追加（保高分条目）。 */
    private String knowledgeText(List<KbEntryRepository.KbEntryRecord> matched) {
        if (matched.isEmpty()) {
            return "（无命中条目）";
        }
        StringBuilder text = new StringBuilder();
        for (KbEntryRepository.KbEntryRecord entry : matched) {
            String block = "### [" + (entry.category() == null ? "未分类" : entry.category())
                    + "] " + entry.title() + "\n"
                    + excerpt(entry.content()) + "\n\n";
            if (text.length() + block.length() > KNOWLEDGE_MAX_CHARS) {
                break;
            }
            text.append(block);
        }
        return text.isEmpty() ? "（无命中条目）" : text.toString().strip();
    }

    private static String excerpt(String content) {
        return content.length() > ENTRY_EXCERPT_MAX
                ? content.substring(0, ENTRY_EXCERPT_MAX) + "…（截断）" : content;
    }

    /** 超限从最旧行整行裁剪（保最新上下文；单行超长按上限硬截）。 */
    static String clipOldestLines(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        String clipped = text.substring(text.length() - maxChars);
        int firstLine = clipped.indexOf('\n');
        return firstLine >= 0 && firstLine + 1 < clipped.length()
                ? clipped.substring(firstLine + 1) : clipped;
    }

    /** 按码点边界截断（P28 审查 P3-4：substring 劈开代理对会产生非法半字符）。 */
    static String truncateAtCodePoint(String text, int maxChars) {
        int end = Math.min(maxChars, text.length());
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    private static String referencesJson(List<Reference> references) {
        ArrayNode array = JSON.createArrayNode();
        for (Reference reference : references) {
            ObjectNode node = array.addObject();
            node.put("id", reference.id());
            node.put("title", reference.title());
            node.put("category", reference.category());
        }
        return array.toString();
    }
}
