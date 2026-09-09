package com.flexforge.issue.domain;

import com.flexforge.common.PublicApi;

import java.time.Instant;
import java.util.List;

/** Issue 持久化端口（issue/issue_label/issue_comment/issue_transition/requirement_spec）。 */
@PublicApi
public interface IssueRepository {

    IssueRecord insertIssue(String id, String title, String description, String createdBy,
                            List<String> labels);

    IssueRecord findIssue(String issueId);

    /** 状态迁移原子落库：issue.status 更新 + issue_transition 行（同一事务）。 */
    IssueRecord applyTransition(String issueId, IssueStatus from, IssueStatus to,
                                String operator, String reason);

    List<IssueRecord> listIssues(IssueStatus status, int offset, int limit);

    /** 按创建者过滤的列表（P23 FR-ISSUE-07：USER 视角范围收口，服务端强制）。 */
    List<IssueRecord> listIssuesByCreator(String creator, int offset, int limit);

    /** 标记已发布（P23：确认并推送；幂等——已发布时不覆盖时间）。 */
    void markPublished(String issueId);

    void replaceLabels(String issueId, List<String> labels);

    void updateAssignee(String issueId, String assignee);

    void insertComment(String issueId, String author, String body);

    List<IssueCommentRecord> commentsOf(String issueId);

    List<IssueTransitionRecord> transitionsOf(String issueId);

    SpecRevisionRecord insertSpec(String issueId, SpecContent content, String createdBy);

    SpecRevisionRecord latestSpec(String issueId);

    List<SpecRevisionRecord> specRevisionsOf(String issueId);

    /** 规格保存入参（校验结论由调用方经 RequirementSchema 判定后随版本留存；
     * briefJson=P23 三段简报，提示词 v3 规格轮必带，手工保存可为 null）。 */
    @PublicApi
    record SpecContent(int schemaVersion, String specJson, boolean valid, String errorsJson,
                       String briefJson) {
    }

    @PublicApi
    record IssueRecord(String id, String title, String description, IssueStatus status,
                       String createdBy, String assignedTo, List<String> labels,
                       Instant createdAt, Instant updatedAt, Instant publishedAt) {
    }

    @PublicApi
    record IssueCommentRecord(String id, String issueId, String author, String body,
                              Instant createdAt) {
    }

    @PublicApi
    record IssueTransitionRecord(String id, String issueId, IssueStatus fromStatus,
                                 IssueStatus toStatus, String operator, String reason,
                                 Instant createdAt) {
    }

    /** 规格版本行（valid + 校验错误快照随版本留存，FR-ISSUE-04 版本审计；
     * briefJson=三段简报快照（P23，无简报的版本为 null）。 */
    @PublicApi
    record SpecRevisionRecord(String id, String issueId, int schemaVersion, int revision,
                              String specJson, boolean valid, String validationErrors,
                              String briefJson, String createdBy, Instant createdAt) {
    }
}
