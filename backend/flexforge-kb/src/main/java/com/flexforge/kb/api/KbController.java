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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库与 AI 助手接口（docs/03 §8、FR-KB-01..04）：条目读/提问/会话为登录
 * 用户能力；条目写仅 ADMIN（@RequireRole 服务端收口，S2——前端隐藏只是体验）。
 * 会话标识一律取认证主体，客户端不可指定他人会话。
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

    public record AskResponse(String answer, List<KbAssistantService.Reference> references) {
    }

    public record ClearResponse(int removed) {
    }

    public record MessageView(String id, String role, String content,
                              List<KbAssistantService.Reference> references) {
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
        return assistant.messagesOf(actor(principal)).stream()
                .map(m -> new MessageView(m.id(), m.role(), m.content(),
                        referencesOf(m)))
                .toList();
    }

    @PostMapping("/ask")
    public AskResponse ask(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
            @RequestBody AskRequest request) {
        KbAssistantService.AskOutcome outcome =
                assistant.ask(actor(principal), request.question());
        return new AskResponse(outcome.answer(), outcome.references());
    }

    @DeleteMapping("/messages")
    public ClearResponse clear(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal) {
        return new ClearResponse(assistant.clear(actor(principal)));
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
}
