package com.flexforge.issue.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 状态机迁移矩阵（docs/03 §7、FR-ISSUE-02；RB-ISSUE 要素 1）。 */
class IssueStatusTest {

    @Test
    void mainChainTransitionsAreLegal() {
        assertThat(IssueStatus.SUBMITTED.canTransitionTo(IssueStatus.APPROVED)).isTrue();
        assertThat(IssueStatus.APPROVED.canTransitionTo(IssueStatus.IN_TESTING)).isTrue();
        assertThat(IssueStatus.IN_TESTING.canTransitionTo(IssueStatus.TESTED)).isTrue();
        assertThat(IssueStatus.TESTED.canTransitionTo(IssueStatus.DONE)).isTrue();
    }

    @Test
    void sideExitsAndIterationReturnsAreLegal() {
        assertThat(IssueStatus.SUBMITTED.canTransitionTo(IssueStatus.RETURNED)).isTrue();
        assertThat(IssueStatus.RETURNED.canTransitionTo(IssueStatus.SUBMITTED)).isTrue();
        assertThat(IssueStatus.APPROVED.canTransitionTo(IssueStatus.DEV_FAILED)).isTrue();
        assertThat(IssueStatus.DEV_FAILED.canTransitionTo(IssueStatus.APPROVED)).isTrue();
        assertThat(IssueStatus.IN_TESTING.canTransitionTo(IssueStatus.FEEDBACK)).isTrue();
        assertThat(IssueStatus.FEEDBACK.canTransitionTo(IssueStatus.APPROVED)).isTrue();
        assertThat(IssueStatus.TESTED.canTransitionTo(IssueStatus.CLOSED)).isTrue();
    }

    @Test
    void illegalJumpsAndTerminalStatesRejected() {
        assertThat(IssueStatus.SUBMITTED.canTransitionTo(IssueStatus.DONE)).isFalse();
        assertThat(IssueStatus.SUBMITTED.canTransitionTo(IssueStatus.IN_TESTING)).isFalse();
        assertThat(IssueStatus.APPROVED.canTransitionTo(IssueStatus.SUBMITTED)).isFalse();
        for (IssueStatus target : IssueStatus.values()) {
            assertThat(IssueStatus.DONE.canTransitionTo(target)).isFalse();
            assertThat(IssueStatus.CLOSED.canTransitionTo(target)).isFalse();
        }
    }

    @Test
    void sideExitsRequireReason() {
        for (IssueStatus from : IssueStatus.values()) {
            for (IssueStatus to : IssueStatus.values()) {
                boolean requires = to == IssueStatus.RETURNED || to == IssueStatus.DEV_FAILED
                        || to == IssueStatus.FEEDBACK || to == IssueStatus.CLOSED;
                assertThat(from.transitionRequiresReason(to)).isEqualTo(requires);
            }
        }
    }
}
