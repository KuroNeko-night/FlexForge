-- P30：需求工坊会话（docs/02 FR-ISSUE-09、docs/09 P30）。
-- 按用户隔离的工坊对话消息；issue_id 可空——assistant 消息在工具执行创建
-- 需求后回填关联（确认卡渲染依据）。seq 身份列保证成对消息确定性排序
-- （V018 同口径：事务时间戳并列不可排序）。
CREATE TABLE IF NOT EXISTS issue_workshop_message (
    id         VARCHAR(64) PRIMARY KEY,
    user_id    VARCHAR(64)  NOT NULL,
    role       VARCHAR(12)  NOT NULL,
    content    TEXT         NOT NULL,
    issue_id   VARCHAR(64),
    seq        BIGINT GENERATED ALWAYS AS IDENTITY,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_workshop_role CHECK (role IN ('user', 'assistant')),
    CONSTRAINT ck_workshop_content CHECK (char_length(content) BETWEEN 1 AND 8000)
);

CREATE INDEX IF NOT EXISTS idx_workshop_user ON issue_workshop_message (user_id, seq);
