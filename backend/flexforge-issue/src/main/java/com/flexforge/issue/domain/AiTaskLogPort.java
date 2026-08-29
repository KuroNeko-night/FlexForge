package com.flexforge.issue.domain;

import com.flexforge.common.PublicApi;

/** AI 任务记录端口（V011 ai_task_log，docs/12 §2-3：实验数据随实现积累）。 */
@PublicApi
public interface AiTaskLogPort {

    void insert(TaskLogEntry entry);

    /** 某 Issue 某类任务的既有记录数（澄清轮次累计用）。 */
    int countOf(String issueId, String kind);

    /** 结构化任务记录（不存提示词全文/模型原始输出/密钥与请求头）。 */
    @PublicApi
    record TaskLogEntry(String issueId, String kind, String model, String promptVersion,
                        int clarifyRounds, int retries, boolean outputValid, String errorCode,
                        long durationMs) {
    }
}
