-- P28：知识库与 AI 助手（docs/02 FR-KB-01..04、docs/09 P28）。
-- kb_entry：企业知识条目（ADMIN 维护，登录可读）；kb_chat_message：助手会话
-- 按用户隔离（仅本人可见/可清空）。引用条目以 references_json 存 [{id,title,category}]，
-- 不存提示词全文与模型原始请求（密钥/条目正文不进日志口径同 docs/13 §3.6-7）。
-- seq 身份列保证同事务成对消息（created_at 同值）的确定性排序。
CREATE TABLE IF NOT EXISTS kb_entry (
    id         VARCHAR(64) PRIMARY KEY,
    title      VARCHAR(120) NOT NULL,
    category   VARCHAR(40),
    content    TEXT          NOT NULL,
    created_by VARCHAR(64)   NOT NULL,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_kb_entry_title CHECK (char_length(title) BETWEEN 1 AND 120),
    CONSTRAINT ck_kb_entry_category CHECK (category IS NULL OR char_length(category) BETWEEN 1 AND 40),
    CONSTRAINT ck_kb_entry_content CHECK (char_length(content) BETWEEN 1 AND 20000)
);

CREATE INDEX IF NOT EXISTS idx_kb_entry_updated ON kb_entry (updated_at DESC);

CREATE TABLE IF NOT EXISTS kb_chat_message (
    id          VARCHAR(64) PRIMARY KEY,
    user_id     VARCHAR(64)  NOT NULL,
    role        VARCHAR(12)  NOT NULL,
    content     TEXT         NOT NULL,
    references_json TEXT,
    seq         BIGINT GENERATED ALWAYS AS IDENTITY,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_kb_chat_role CHECK (role IN ('user', 'assistant')),
    CONSTRAINT ck_kb_chat_content CHECK (char_length(content) BETWEEN 1 AND 8000)
);

CREATE INDEX IF NOT EXISTS idx_kb_chat_user ON kb_chat_message (user_id, seq);
