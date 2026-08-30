-- P15 设置页：AI 模型运行时配置（FR-SETUP-01，docs/13 §3.6-5）
-- 单行配置表（id 恒为 1，CHECK 兜底）：provider/base_url/model 为可回显字段；
-- api_key_cipher 为 AES-256-GCM 密文（base64(iv||ct)，加密密钥由 AUTH_JWT_SECRET
-- 派生，S4：明文只在加密前内存与 Authorization 头）；api_key_hint 只存尾 4 位掩码。
CREATE TABLE ai_provider_config (
    id               SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    provider         VARCHAR(16)  NOT NULL DEFAULT 'fixture',
    base_url         VARCHAR(500),
    model            VARCHAR(100),
    api_key_cipher   TEXT,
    api_key_hint     VARCHAR(8),
    updated_by       VARCHAR(64)  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
