package com.flexforge.issue.api;

import com.flexforge.auth.AuthPrincipal;
import com.flexforge.auth.api.JwtAuthFilter;
import com.flexforge.auth.core.AuthService;
import com.flexforge.common.ApiConstants;
import com.flexforge.common.PublicApi;
import com.flexforge.issue.application.IssueWorkshopService;
import com.flexforge.issue.domain.IssueWorkshopRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 需求工坊接口（docs/03 §8、FR-ISSUE-09）：登录用户能力（创建/追问以本人
 * 为操作者，服务端收口），会话按用户隔离。
 */
@PublicApi
@RestController
@RequestMapping(ApiConstants.API_V1 + "/issues/workshop")
public class IssueWorkshopController {

    private final IssueWorkshopService workshop;
    private final AuthService authService;

    public IssueWorkshopController(IssueWorkshopService workshop, AuthService authService) {
        this.workshop = workshop;
        this.authService = authService;
    }

    private String actor(AuthPrincipal principal) {
        return authService.currentUser(principal).username();
    }

    public record SendRequest(String message) {
    }

    public record MessageView(String id, String role, String content, String issueId) {
    }

    public record SendResponse(String reply, String issueId, String issueTitle) {
    }

    public record ClearResponse(int removed) {
    }

    @GetMapping
    public List<MessageView> messages(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal) {
        return workshop.messagesOf(actor(principal)).stream()
                .map(m -> new MessageView(m.id(), m.role(), m.content(), m.issueId()))
                .toList();
    }

    @PostMapping
    public SendResponse send(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
            @RequestBody SendRequest request) {
        IssueWorkshopService.WorkshopOutcome outcome =
                workshop.send(actor(principal), request.message());
        return new SendResponse(outcome.reply(), outcome.issueId(), outcome.issueTitle());
    }

    @DeleteMapping
    public ClearResponse clear(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal) {
        return new ClearResponse(workshop.clear(actor(principal)));
    }
}
