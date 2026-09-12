package com.flexforge.kb.api;

import com.flexforge.auth.AuthPrincipal;
import com.flexforge.auth.Roles;
import com.flexforge.auth.api.JwtAuthFilter;
import com.flexforge.auth.api.RequireRole;
import com.flexforge.auth.core.AuthService;
import com.flexforge.common.ApiConstants;
import com.flexforge.common.PublicApi;
import com.flexforge.kb.application.KbAssistantService;
import com.flexforge.kb.application.KbEntryService;
import com.flexforge.kb.domain.KbChatRepository;
import com.flexforge.kb.domain.KbEntryRepository;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 知识库与 AI 助手接口（docs/03 §8、FR-KB-01..05）：条目读/提问/会话为登录
 * 用户能力；条目写仅 ADMIN（@RequireRole 服务端收口，S2）。会话标识一律取
 * 认证主体。P29：/ask 双 consumes（JSON 兼容 + multipart 携带附件），附件
 * 下载按消息归属校验（他人 404 防枚举）。
 */
@PublicApi
@RestController
@RequestMapping(ApiConstants.API_V1 + "/kb")
public class KbController {

    private final KbEntryService entries;
    private final KbAssistantService assistant;
    private final AuthService authService;

    public KbController(KbEntryService entries, KbAssistantService assistant,
                        AuthService authService) {
        this.entries = entries;
        this.assistant = assistant;
        this.authService = authService;
    }

    private String actor(AuthPrincipal principal) {
        return authService.currentUser(principal).username();
    }

    public record EntryPayload(String title, String category, String content) {
    }

    public record AskRequest(String question) {
    }

    public record AskResponse(String answer, List<KbAssistantService.Reference> references,
                              List<KbChatRepository.KbAttachmentView> attachments) {
    }

    public record ClearResponse(int removed) {
    }

    public record MessageView(String id, String role, String content,
                              List<KbAssistantService.Reference> references,
                              List<KbChatRepository.KbAttachmentView> attachments) {
    }

    @GetMapping("/entries")
    public List<KbEntryRepository.KbEntryRecord> listEntries() {
        return entries.list();
    }

    @PostMapping("/entries")
    @RequireRole(Roles.ADMIN)
    public KbEntryRepository.KbEntryRecord create(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
            @RequestBody EntryPayload payload) {
        return entries.create(actor(principal), payload.title(),
                payload.category(), payload.content());
    }

    @PutMapping("/entries/{id}")
    @RequireRole(Roles.ADMIN)
    public KbEntryRepository.KbEntryRecord update(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
            @PathVariable String id, @RequestBody EntryPayload payload) {
        return entries.update(actor(principal), id, payload.title(),
                payload.category(), payload.content());
    }

    @DeleteMapping("/entries/{id}")
    @RequireRole(Roles.ADMIN)
    public void delete(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
            @PathVariable String id) {
        entries.delete(actor(principal), id);
    }

    @GetMapping("/messages")
    public List<MessageView> messages(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal) {
        List<KbChatRepository.KbMessageRecord> records =
                assistant.messagesOf(actor(principal));
        List<String> messageIds = records.stream()
                .map(KbChatRepository.KbMessageRecord::id).toList();
        var byMessage = new java.util.HashMap<String, List<KbChatRepository.KbAttachmentView>>();
        for (KbChatRepository.KbAttachmentView view : assistant.attachmentsOf(messageIds)) {
            byMessage.computeIfAbsent(view.messageId(), k -> new ArrayList<>()).add(view);
        }
        return records.stream()
                .map(m -> new MessageView(m.id(), m.role(), m.content(),
                        referencesOf(m), byMessage.getOrDefault(m.id(), List.of())))
                .toList();
    }

    /** JSON 提问（P28 契径，无附件）。 */
    @PostMapping(value = "/ask", consumes = MediaType.APPLICATION_JSON_VALUE)
    public AskResponse askJson(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
            @RequestBody AskRequest request) {
        return toResponse(assistant.ask(actor(principal), request.question()));
    }

    /** multipart 提问（P29）：question 字段 + 可选 files（白名单/上限服务端强制）。 */
    @PostMapping(value = "/ask", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AskResponse askMultipart(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
            @RequestParam("question") String question,
            @RequestParam(value = "files", required = false) List<MultipartFile> files)
            throws java.io.IOException {
        List<KbAssistantService.IncomingAttachment> incoming = new ArrayList<>();
        if (files != null) {
            for (MultipartFile file : files) {
                incoming.add(new KbAssistantService.IncomingAttachment(
                        file.getOriginalFilename(), contentTypeOf(file), file.getBytes()));
            }
        }
        return toResponse(assistant.ask(actor(principal), question, incoming));
    }

    /** 附件下载（本人；按消息归属校验，他人/不存在 404 防枚举）。 */
    @GetMapping("/attachments/{id}")
    public ResponseEntity<byte[]> downloadAttachment(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
            @PathVariable String id) {
        KbChatRepository.OwnedAttachment owned = assistant.findOwnedAttachment(id);
        if (owned == null || !owned.ownerId().equals(actor(principal))) {
            throw new NoSuchElementException("附件不存在: " + id);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment().filename(
                owned.filename(), StandardCharsets.UTF_8).build());
        headers.set("X-Content-Type-Options", "nosniff");
        return ResponseEntity.ok().headers(headers)
                .contentType(MediaType.parseMediaType(safeMediaType(owned.contentType())))
                .body(owned.data());
    }

    @DeleteMapping("/messages")
    public ClearResponse clear(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal) {
        return new ClearResponse(assistant.clear(actor(principal)));
    }

    private static AskResponse toResponse(KbAssistantService.AskOutcome outcome) {
        return new AskResponse(outcome.answer(), outcome.references(), outcome.attachments());
    }

    /** 引用条目解析失败不阻断会话回放（历史引用快照容错降级为空）。 */
    private static List<KbAssistantService.Reference> referencesOf(
            KbChatRepository.KbMessageRecord message) {
        if (message.referencesJson() == null || message.referencesJson().isBlank()) {
            return List.of();
        }
        try {
            return KbReferences.parse(message.referencesJson());
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** multipart 缺省/非法 Content-Type 兜底为 application/octet-stream。 */
    private static String contentTypeOf(MultipartFile file) {
        String type = file.getContentType();
        return type == null || type.isBlank()
                ? "application/octet-stream" : type;
    }

    /** 存储的类型值不可信（客户端可任意填写）：白名单外一律 octet-stream。 */
    private static String safeMediaType(String stored) {
        try {
            MediaType.parseMediaType(stored);
            return stored;
        } catch (RuntimeException e) {
            return "application/octet-stream";
        }
    }
}
