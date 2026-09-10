package com.flexforge.issue.application;

import com.flexforge.common.PublicApi;
import com.flexforge.common.audit.AuditEventPort;
import com.flexforge.common.audit.AuditEvents;
import com.flexforge.issue.domain.IssueRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Issue 协作面（FR-ISSUE-01）：创建、评论、标签替换、指派、列表与详情。
 * 所有写动作记平台审计（issue.create/comment/labels.replace/assign）。
 */
@PublicApi
@Service
public class IssueService {

    private final IssueRepository repository;
    private final AuditEventPort audit;
    private final Clock clock;

    public IssueService(IssueRepository repository, AuditEventPort audit, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    public IssueRepository.IssueRecord create(String actor, String title, String description,
                                              List<String> labels) {
        if (title == null || title.isBlank() || title.length() > 120) {
            throw new IllegalArgumentException("title 必填且 ≤120 字符");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description 必填");
        }
        requireValidLabels(labels);
        String id = "iss-" + UUID.randomUUID();
        IssueRepository.IssueRecord issue =
                repository.insertIssue(id, title, description, actor, labels);
        audit.record(AuditEvents.of(actor, "issue.create", id, "success", clock));
        return issue;
    }

    /** 标签形状校验（创建与替换同口径，防 DB 宽度违规变 500）。 */
    private static void requireValidLabels(List<String> labels) {
        for (String label : labels) {
            if (label.isBlank() || label.length() > 40) {
                throw new IllegalArgumentException("label 须为 1..40 字符: " + label);
            }
        }
    }

    public IssueRepository.IssueRecord require(String issueId) {
        IssueRepository.IssueRecord issue = repository.findIssue(issueId);
        if (issue == null) {
            throw new NoSuchElementException("Issue 不存在: " + issueId);
        }
        return issue;
    }

    public List<IssueRepository.IssueRecord> list(String status, int page, int pageSize) {
        int safePage = Math.min(Math.max(1, page), 10_000);
        return repository.listIssues(status == null ? null
                : com.flexforge.issue.domain.IssueStatus.fromName(status),
                (safePage - 1) * pageSize, pageSize);
    }

    /** 按创建者过滤的列表（P23 FR-ISSUE-07：USER 视角范围收口）。 */
    public List<IssueRepository.IssueRecord> listMine(String creator,
                                                      int page, int pageSize) {
        int safePage = Math.min(Math.max(1, page), 10_000);
        return repository.listIssuesByCreator(creator,
                (safePage - 1) * pageSize, pageSize);
    }

    public void comment(String actor, String issueId, String body) {
        require(issueId);
        if (body == null || body.isBlank() || body.length() > 2000) {
            throw new IllegalArgumentException("评论内容必填且 ≤2000 字符");
        }
        repository.insertComment(issueId, actor, body);
        audit.record(AuditEvents.of(actor, "issue.comment", issueId, "success", clock));
    }

    public List<IssueRepository.IssueCommentRecord> comments(String issueId) {
        return repository.commentsOf(require(issueId).id());
    }

    public IssueRepository.IssueRecord replaceLabels(String actor, String issueId,
                                                     List<String> labels) {
        require(issueId);
        requireValidLabels(labels);
        repository.replaceLabels(issueId, labels);
        audit.record(AuditEvents.of(actor, "issue.labels.replace", issueId, "success", clock));
        return require(issueId);
    }

    public IssueRepository.IssueRecord assign(String actor, String issueId, String assignee) {
        require(issueId);
        if (assignee != null && !assignee.isBlank() && assignee.length() > 64) {
            throw new IllegalArgumentException("assignee 须 ≤64 字符");
        }
        repository.updateAssignee(issueId, assignee == null || assignee.isBlank()
                ? null : assignee);
        audit.record(AuditEvents.of(actor, "issue.assign", issueId, "success", clock));
        return require(issueId);
    }

    public List<IssueRepository.IssueTransitionRecord> transitions(String issueId) {
        return repository.transitionsOf(require(issueId).id());
    }
}
