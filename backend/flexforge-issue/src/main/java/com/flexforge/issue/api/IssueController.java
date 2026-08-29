package com.flexforge.issue.api;

import com.flexforge.ai.spec.SpecPreview;
import com.flexforge.auth.AuthPrincipal;
import com.flexforge.auth.Roles;
import com.flexforge.auth.api.JwtAuthFilter;
import com.flexforge.auth.api.RequireRole;
import com.flexforge.auth.core.AuthService;
import com.flexforge.common.ApiConstants;
import com.flexforge.common.PublicApi;
import com.flexforge.issue.application.IssueService;
import com.flexforge.issue.application.IssueWorkflowService;
import com.flexforge.issue.domain.IssueRepository;
import com.flexforge.issue.domain.IssueStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Issue 接口（docs/03 §8、FR-ISSUE-01/02/04/06）：创建/评论/标签/指派为
 * 登录用户可用（普通用户提交与跟踪，docs/02 §1）；状态迁移、规格保存与
 * 预览为开发者职责（审核需求/配置元数据，与 meta 写权限同口径）。
 */
@PublicApi
@RestController
@RequestMapping(ApiConstants.API_V1 + "/issues")
public class IssueController {

    private final IssueService issues;
    private final IssueWorkflowService workflow;
    private final AuthService authService;

    public IssueController(IssueService issues, IssueWorkflowService workflow,
                           AuthService authService) {
        this.issues = issues;
        this.workflow = workflow;
        this.authService = authService;
    }

    public record CreateIssueRequest(String title, String description, List<String> labels) {
    }

    public record CommentRequest(String body) {
    }

    public record LabelsRequest(List<String> labels) {
    }

    public record AssigneeRequest(String assignee) {
    }

    public record TransitionRequest(String to, String reason) {
    }

    @PostMapping
    public IssueRepository.IssueRecord create(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
            @RequestBody CreateIssueRequest request) {
        return issues.create(actor(principal), request.title(), request.description(),
                request.labels() == null ? List.of() : request.labels());
    }

    @GetMapping
    public List<IssueRepository.IssueRecord> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return issues.list(status, page, Math.min(Math.max(1, pageSize), 100));
    }

    @GetMapping("/{issueId}")
    public IssueRepository.IssueRecord detail(@PathVariable String issueId) {
        return issues.require(issueId);
    }

    @PostMapping("/{issueId}/comments")
    public void comment(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
            @PathVariable String issueId, @RequestBody CommentRequest request) {
        issues.comment(actor(principal), issueId, request.body());
    }

    @GetMapping("/{issueId}/comments")
    public List<IssueRepository.IssueCommentRecord> comments(@PathVariable String issueId) {
        return issues.comments(issueId);
    }

    @PatchMapping("/{issueId}/labels")
    public IssueRepository.IssueRecord replaceLabels(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
            @PathVariable String issueId, @RequestBody LabelsRequest request) {
        return issues.replaceLabels(actor(principal), issueId,
                request.labels() == null ? List.of() : request.labels());
    }

    @PatchMapping("/{issueId}/assignee")
    public IssueRepository.IssueRecord assign(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
            @PathVariable String issueId, @RequestBody AssigneeRequest request) {
        return issues.assign(actor(principal), issueId, request.assignee());
    }

    @GetMapping("/{issueId}/transitions")
    public List<IssueRepository.IssueTransitionRecord> transitions(
            @PathVariable String issueId) {
        return issues.transitions(issueId);
    }

    /** 状态迁移（docs/03 §8 POST /issues/{id}/transition；开发者口径）。 */
    @PostMapping("/{issueId}/transition")
    @RequireRole(Roles.DEVELOPER)
    public IssueRepository.IssueRecord transition(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
            @PathVariable String issueId, @RequestBody TransitionRequest request) {
        return workflow.transition(actor(principal), issueId,
                IssueStatus.fromName(request.to()), request.reason());
    }

    /** 保存规格新版本（FR-ISSUE-04/06：AI 草稿或手工编辑，均可迭代保存）。 */
    @PutMapping("/{issueId}/spec")
    @RequireRole(Roles.DEVELOPER)
    public IssueRepository.SpecRevisionRecord saveSpec(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
            @PathVariable String issueId, @RequestBody JsonNode spec) {
        return workflow.saveSpec(actor(principal), issueId, spec);
    }

    @GetMapping("/{issueId}/spec")
    public IssueRepository.SpecRevisionRecord latestSpec(@PathVariable String issueId) {
        return workflow.latestSpec(issueId);
    }

    @GetMapping("/{issueId}/spec/revisions")
    public List<IssueRepository.SpecRevisionRecord> specRevisions(
            @PathVariable String issueId) {
        return workflow.specRevisions(issueId);
    }

    /** 预览将要生成的插件资源（docs/09 P10 验收 4，开发者）。 */
    @GetMapping("/{issueId}/preview")
    @RequireRole(Roles.DEVELOPER)
    public SpecPreview.Preview preview(@PathVariable String issueId) {
        return workflow.preview(issueId);
    }

    private String actor(AuthPrincipal principal) {
        return authService.currentUser(principal).username();
    }
}
