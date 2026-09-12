package com.flexforge.kb.application;

import com.flexforge.ai.model.ModelPort;
import com.flexforge.common.PublicApi;
import com.flexforge.common.audit.AuditEventPort;
import com.flexforge.common.audit.AuditEvents;
import com.flexforge.kb.domain.KbChatRepository;
import com.flexforge.kb.domain.KbEntryRepository;
import com.flexforge.kb.domain.KbRetrieval;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI 助手编排（FR-KB-02/03/04，docs/13 §3.6-7）：检索 Top-K → 模板
 * kb-assistant-v1 渲染 → ModelPort（http/fixture 路由唯一通道）→ 用户与
 * 助手消息同事务落库（模型失败不落半截会话）。会话按 user_id 隔离，
 * 调用方传认证主体标识，不接受客户端指定他人会话。
 */
@PublicApi
@Service
public class KbAssistantService {

    /** 提问与注入预算（FR-KB-02/03、docs/13 §3.6-7 硬上限）。 */
    public static final int QUESTION_MAX = 2000;
    public static final int HISTORY_MESSAGES = 8;
    public static final int HISTORY_MAX_CHARS = 4000;
    public static final int ENTRY_EXCERPT_MAX = 1500;
    public static final int KNOWLEDGE_MAX_CHARS = 6000;
    public static final int ANSWER_STORE_MAX = 8000;
    /** 会话查询上限（UI 回放用）。 */
    public static final int HISTORY_LOAD_LIMIT = 100;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 助手回答与引用条目（references 为 [{id,title,category}] 快照）。 */
    @PublicApi
    public record AskOutcome(String answer, List<Reference> references) {
    }

    @PublicApi
    public record Reference(String id, String title, String category) {
    }

    private final KbEntryRepository entries;
    private final KbChatRepository chat;
    private final ModelPort model;
    private final AuditEventPort audit;
    private final Clock clock;

    public KbAssistantService(KbEntryRepository entries, KbChatRepository chat,
                              ModelPort model, AuditEventPort audit, Clock clock) {
        this.entries = entries;
        this.chat = chat;
        this.model = model;
        this.audit = audit;
        this.clock = clock;
    }

    /** 某用户会话消息（升序，最近 HISTORY_LOAD_LIMIT 条）。 */
    public List<KbChatRepository.KbMessageRecord> messagesOf(String userId) {
        return chat.recentOf(userId, HISTORY_LOAD_LIMIT);
    }

    /** 清空本人会话（返回删除条数）。 */
    public int clear(String operator) {
        int removed = chat.deleteAllOf(operator);
        audit.record(AuditEvents.of(operator, "kb.clear", operator, "cleared", clock));
        return removed;
    }

    /** 提问：模型失败（含不可用）原样上抛，不产生任何会话写入（FR-KB-04）。 */
    public AskOutcome ask(String operator, String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        String trimmed = question.strip();
        if (trimmed.length() > QUESTION_MAX) {
            throw new IllegalArgumentException("问题超过 " + QUESTION_MAX + " 字符上限");
        }
        List<KbEntryRepository.KbEntryRecord> matched =
                KbRetrieval.topMatches(trimmed, entries.listAll());
        String prompt = KbPromptTemplates.render(Map.of(
                "history", historyText(operator),
                "knowledge", knowledgeText(matched),
                "question", trimmed));
        String answer;
        try {
            answer = model.complete(new ModelPort.ModelRequest(
                    KbPromptTemplates.VERSION, prompt)).text().strip();
        } catch (RuntimeException e) {
            audit.record(AuditEvents.of(operator, "kb.ask", operator, "failure", clock));
            throw e;
        }
        List<Reference> references = matched.stream()
                .map(e -> new Reference(e.id(), e.title(), e.category())).toList();
        String stored = answer.length() > ANSWER_STORE_MAX
                ? answer.substring(0, ANSWER_STORE_MAX) : answer;
        chat.insertExchange(
                new KbChatRepository.KbMessageRecord(
                        "kcm-" + UUID.randomUUID(), operator, "user", trimmed, null),
                new KbChatRepository.KbMessageRecord(
                        "kcm-" + UUID.randomUUID(), operator, "assistant", stored,
                        referencesJson(references)));
        audit.record(AuditEvents.of(operator, "kb.ask", operator, "success", clock));
        return new AskOutcome(answer, references);
    }

    /** 会话历史数据段：近 HISTORY_MESSAGES 条，超 HISTORY_MAX_CHARS 从最旧行裁剪。 */
    private String historyText(String userId) {
        List<KbChatRepository.KbMessageRecord> recent =
                chat.recentOf(userId, HISTORY_MESSAGES);
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
