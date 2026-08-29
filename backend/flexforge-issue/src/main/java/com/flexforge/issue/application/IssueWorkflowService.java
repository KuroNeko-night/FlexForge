package com.flexforge.issue.application;

import com.flexforge.ai.spec.RequirementSchema;
import com.flexforge.ai.spec.SpecPreview;
import com.flexforge.common.PublicApi;
import com.flexforge.common.audit.AuditEventPort;
import com.flexforge.common.audit.AuditEvents;
import com.flexforge.issue.domain.IssueRepository;
import com.flexforge.issue.domain.IssueStatus;
import com.flexforge.issue.domain.InvalidTransitionException;
import com.flexforge.issue.domain.IssueRepository.SpecRevisionRecord;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Issue 工作流（docs/03 §7、FR-ISSUE-02/04）：状态迁移由本服务统一执行
 * （合法性/原因/规格门 + issue_transition 记录 + 审计）；规格保存为
 * 追加式新版本（valid 与校验错误快照随版本留存）；预览从合法规格确定性派生。
 */
@PublicApi
@Service
public class IssueWorkflowService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final IssueRepository repository;
    private final AuditEventPort audit;
    private final Clock clock;

    public IssueWorkflowService(IssueRepository repository, AuditEventPort audit, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    /** 执行合法状态迁移：合法性→原因→规格门（批准需最新规格 valid）→原子落库→审计。 */
    public IssueRepository.IssueRecord transition(String operator, String issueId,
                                                  IssueStatus target, String reason) {
        IssueRepository.IssueRecord issue = requireIssue(issueId);
        if (!issue.status().canTransitionTo(target)) {
            throw InvalidTransitionException.illegal(issue.status(), target);
        }
        if (issue.status().transitionRequiresReason(target)
                && (reason == null || reason.isBlank())) {
            throw InvalidTransitionException.missingReason(target);
        }
        if (reason != null && reason.length() > 500) {
            throw new IllegalArgumentException("迁移原因须 ≤500 字符");
        }
        if (target == IssueStatus.APPROVED) {
            requireValidSpec(issueId);
        }
        IssueRepository.IssueRecord updated =
                repository.applyTransition(issueId, issue.status(), target, operator, reason);
        audit.record(AuditEvents.of(operator, "issue.transition", issueId,
                target.name(), clock));
        return updated;
    }

    /** 保存规格新版本：校验快照随版本留存（invalid 也保存，供修改迭代与审计）。 */
    public SpecRevisionRecord saveSpec(String operator, String issueId, JsonNode spec) {
        requireOpen(issueId);
        List<String> errors = RequirementSchema.validate(spec);
        IssueRepository.SpecContent content = new IssueRepository.SpecContent(
                RequirementSchema.CURRENT_VERSION, spec.toString(), errors.isEmpty(),
                JSON.valueToTree(errors).toString());
        SpecRevisionRecord revision = repository.insertSpec(issueId, content, operator);
        audit.record(AuditEvents.of(operator, "issue.spec.update",
                issueId + "#" + revision.revision(),
                errors.isEmpty() ? "valid" : "invalid", clock));
        return revision;
    }

    public SpecRevisionRecord latestSpec(String issueId) {
        SpecRevisionRecord latest = repository.latestSpec(requireIssue(issueId).id());
        if (latest == null) {
            throw new NoSuchElementException("尚无规格版本: " + issueId);
        }
        return latest;
    }

    public List<SpecRevisionRecord> specRevisions(String issueId) {
        requireIssue(issueId);
        return repository.specRevisionsOf(issueId);
    }

    /** 预览将要生成的插件资源（docs/09 P10 验收 4）；无规格 → 404。 */
    public SpecPreview.Preview preview(String issueId) {
        SpecRevisionRecord latest = latestSpec(issueId);
        return SpecPreview.of(JSON.readTree(
                latest.specJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private IssueRepository.IssueRecord requireIssue(String issueId) {
        IssueRepository.IssueRecord issue = repository.findIssue(issueId);
        if (issue == null) {
            throw new NoSuchElementException("Issue 不存在: " + issueId);
        }
        return issue;
    }

    /** 终态 Issue（DONE/CLOSED）不接受规格写入（Issue #22 评论-24，失败路径有测试）。 */
    private IssueRepository.IssueRecord requireOpen(String issueId) {
        IssueRepository.IssueRecord issue = requireIssue(issueId);
        if (issue.status() == IssueStatus.DONE || issue.status() == IssueStatus.CLOSED) {
            throw new IllegalArgumentException(
                    "Issue 已完结（" + issue.status().displayName() + "），不接受规格修改");
        }
        return issue;
    }

    private void requireValidSpec(String issueId) {
        SpecRevisionRecord latest = repository.latestSpec(issueId);
        if (latest == null || !latest.valid()) {
            throw InvalidTransitionException.missingValidSpec();
        }
    }
}
