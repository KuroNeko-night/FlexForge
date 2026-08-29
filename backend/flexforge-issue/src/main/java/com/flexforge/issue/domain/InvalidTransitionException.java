package com.flexforge.issue.domain;

import com.flexforge.common.PublicApi;
import com.flexforge.common.api.ErrorCodes;

/** 非法状态迁移（FR-ISSUE-02）：稳定错误码 invalid_transition，可诊断。 */
@PublicApi
public class InvalidTransitionException extends RuntimeException {

    public InvalidTransitionException(String message) {
        super(message);
    }

    public static InvalidTransitionException illegal(IssueStatus from, IssueStatus to) {
        return new InvalidTransitionException("非法状态迁移: " + from.displayName()
                + " → " + to.displayName());
    }

    public static InvalidTransitionException missingReason(IssueStatus to) {
        return new InvalidTransitionException("迁移到 " + to.displayName() + " 必须填写原因");
    }

    public static InvalidTransitionException missingValidSpec() {
        return new InvalidTransitionException("缺少合法规格（最新版本校验未通过），不能批准");
    }

    public String code() {
        return ErrorCodes.INVALID_TRANSITION;
    }
}
