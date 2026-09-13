package com.flexforge.issue.domain;

import com.flexforge.common.PublicApi;

import java.util.List;

/**
 * 需求工坊会话仓储（V021 issue_workshop_message，FR-ISSUE-09）：按 user_id
 * 隔离，端口调用方传认证主体标识；assistant 消息的 issueId 在工具执行创建
 * 需求后回填（前端确认卡依据）。
 */
@PublicApi
public interface IssueWorkshopRepository {

    /** 某用户最近 limit 条消息（seq 升序）。 */
    List<WorkshopMessageRecord> recentOf(String userId, int limit);

    /** 用户与助手消息成对同事务落库（模型失败零写入）。 */
    void insertExchange(WorkshopMessageRecord userMessage, WorkshopMessageRecord assistantMessage);

    int deleteAllOf(String userId);

    /** 工坊消息：role=user|assistant；issueId 仅工具创建后的 assistant 消息携带。 */
    @PublicApi
    record WorkshopMessageRecord(String id, String userId, String role, String content,
                                 String issueId) {
    }
}
