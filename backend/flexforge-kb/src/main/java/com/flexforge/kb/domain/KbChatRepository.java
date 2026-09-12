package com.flexforge.kb.domain;

import com.flexforge.common.PublicApi;

import java.util.List;

/**
 * 助手会话仓储（V018 kb_chat_message，FR-KB-03）：消息按 user_id 隔离，
 * 端口调用方负责传入本人标识（控制器从认证主体取，不接受客户端指定）。
 */
@PublicApi
public interface KbChatRepository {

    /** 某用户最近 limit 条消息（created_at 升序返回，取尾部上下文用）。 */
    List<KbMessageRecord> recentOf(String userId, int limit);

    /** 用户与助手消息同事务落库（要么同落要么全不落，FR-KB-04）。 */
    void insertExchange(KbMessageRecord userMessage, KbMessageRecord assistantMessage);

    /** 清空某用户会话（仅本人路径调用）。 */
    int deleteAllOf(String userId);

    /** 会话消息：role=user|assistant；referencesJson 仅 assistant 消息携带
     * （[{id,title,category}]，引用条目快照——条目后续编辑/删除不改历史回答）。 */
    @PublicApi
    record KbMessageRecord(String id, String userId, String role, String content,
                           String referencesJson) {
    }
}
