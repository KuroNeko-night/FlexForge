-- P23：文件处理器产物登记（FR-PLUGIN-14，docs/07 §1、docs/13 §3.5-5）。
-- 临时产物：归属创建者，TTL 过期由定时任务清理（行+磁盘目录）；
-- 可重复下载直至过期（downloaded_at 记录首次下载时间，审计口径）。
CREATE TABLE IF NOT EXISTS processor_artifact (
    id            VARCHAR(64) PRIMARY KEY,
    processor_key VARCHAR(120) NOT NULL,
    filename      VARCHAR(200) NOT NULL,
    content_type  VARCHAR(120) NOT NULL,
    size_bytes    BIGINT        NOT NULL,
    storage_dir   VARCHAR(400) NOT NULL,
    created_by    VARCHAR(64)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ  NOT NULL,
    downloaded_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_processor_artifact_expires ON processor_artifact (expires_at);
