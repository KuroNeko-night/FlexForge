-- P29：助手对话附件（docs/02 FR-KB-05、docs/13 §3.6-8）。
-- 附件随用户消息存储（BYTEA 入库随 pgdata 卷存活，容器重建不丢）；提取文本
-- 服务端生成后注入提示词数据段（kb-assistant-v2），图片仅存档不提取。
-- 清空会话经 ON DELETE CASCADE 级联清理附件；下载按消息归属校验（他人 404）。
CREATE TABLE IF NOT EXISTS kb_attachment (
    id             VARCHAR(64) PRIMARY KEY,
    message_id     VARCHAR(64)  NOT NULL,
    filename       VARCHAR(255) NOT NULL,
    content_type   VARCHAR(64)  NOT NULL,
    size_bytes     BIGINT       NOT NULL,
    data           BYTEA        NOT NULL,
    extracted_text TEXT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_kb_attachment_message FOREIGN KEY (message_id)
        REFERENCES kb_chat_message (id) ON DELETE CASCADE,
    CONSTRAINT ck_kb_attachment_size CHECK (size_bytes BETWEEN 1 AND 10485760)
);

CREATE INDEX IF NOT EXISTS idx_kb_attachment_message ON kb_attachment (message_id);
