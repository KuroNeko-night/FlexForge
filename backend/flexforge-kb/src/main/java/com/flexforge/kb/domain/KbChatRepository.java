package com.flexforge.kb.domain;

import com.flexforge.common.PublicApi;

import java.util.List;

/**
 * 助手会话仓储（V018/V019，FR-KB-03/05）：消息按 user_id 隔离，端口调用方
 * 负责传入本人标识（控制器从认证主体取，不接受客户端指定）。附件随用户消息
 * 同事务写入（模型失败整体回滚含附件）；清空会话级联清理附件（V019 FK）。
 */
@PublicApi
public interface KbChatRepository {

    /** 某用户最近 limit 条消息（created_at 升序返回，取尾部上下文用）。 */
    List<KbMessageRecord> recentOf(String userId, int limit);

    /** 用户与助手消息（及其附件行）同事务落库——要么同落要么全不落（FR-KB-04/05）。 */
    void insertExchange(KbMessageRecord userMessage, KbMessageRecord assistantMessage,
                        List<KbAttachmentRecord> attachments);

    /** 消息附件轻量视图（不含字节载荷，会话回放用）。 */
    List<KbAttachmentView> attachmentsOf(List<String> messageIds);

    /** 附件字节与归属（下载用；不存在或无归属返回 null → 404 防枚举）。 */
    OwnedAttachment findOwned(String attachmentId);

    /** 清空某用户会话（仅本人路径调用；附件经 FK 级联清理）。 */
    int deleteAllOf(String userId);

    /** 会话消息：role=user|assistant；referencesJson 仅 assistant 消息携带
     * （[{id,title,category}]，引用条目快照——条目后续编辑/删除不改历史回答）。 */
    @PublicApi
    record KbMessageRecord(String id, String userId, String role, String content,
                           String referencesJson) {
    }

    /** 附件行（extractedText 可空=图片或提取失败仅存档）。 */
    @PublicApi
    record KbAttachmentRecord(String id, String messageId, String filename, String contentType,
                              long sizeBytes, byte[] data, String extractedText) {
    }

    /** 回放视图（无字节）。 */
    @PublicApi
    record KbAttachmentView(String id, String messageId, String filename, String contentType,
                            long sizeBytes) {
    }

    /** 下载载荷（ownerId 用于服务端归属校验后才放行）。 */
    @PublicApi
    record OwnedAttachment(String ownerId, String filename, String contentType, byte[] data) {
    }
}
