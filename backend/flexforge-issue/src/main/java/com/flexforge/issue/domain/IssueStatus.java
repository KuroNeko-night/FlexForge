package com.flexforge.issue.domain;

import com.flexforge.common.PublicApi;

import java.util.Map;
import java.util.Set;

/**
 * Issue 状态机（docs/03 §7、FR-ISSUE-02）。主链：
 * SUBMITTED→APPROVED→IN_TESTING→TESTED→DONE；旁路：RETURNED/DEV_FAILED/
 * FEEDBACK/CLOSED（须原因）。迭代回退（2026-08-29 docs/03 §7 补记）：
 * RETURNED→SUBMITTED、FEEDBACK/DEV_FAILED→APPROVED。
 * 迁移只经 {@code IssueWorkflowService} 统一执行（记操作者/原因/时间）。
 */
@PublicApi
public enum IssueStatus {

    SUBMITTED("待审核"),
    APPROVED("已批准"),
    RETURNED("退回修改"),
    IN_TESTING("待测试"),
    DEV_FAILED("开发失败"),
    TESTED("测试通过"),
    FEEDBACK("反馈修复"),
    DONE("已完成"),
    CLOSED("已关闭");

    private static final Map<IssueStatus, Set<IssueStatus>> TRANSITIONS = Map.of(
            SUBMITTED, Set.of(APPROVED, RETURNED),
            RETURNED, Set.of(SUBMITTED),
            APPROVED, Set.of(IN_TESTING, DEV_FAILED),
            DEV_FAILED, Set.of(APPROVED),
            IN_TESTING, Set.of(TESTED, FEEDBACK),
            FEEDBACK, Set.of(APPROVED),
            TESTED, Set.of(DONE, CLOSED));

    private final String displayName;

    IssueStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean canTransitionTo(IssueStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    /** 旁路迁移（退回/失败/反馈/关闭）必须携带原因（docs/09 P10 实施内容）。 */
    public boolean transitionRequiresReason(IssueStatus target) {
        return target == RETURNED || target == DEV_FAILED
                || target == FEEDBACK || target == CLOSED;
    }

    public static IssueStatus fromName(String name) {
        for (IssueStatus status : values()) {
            if (status.name().equals(name)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知 Issue 状态: " + name);
    }
}
