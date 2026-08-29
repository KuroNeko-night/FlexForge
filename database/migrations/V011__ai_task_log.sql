-- P11：AI 任务结构化记录（docs/03 §9、docs/09 P11、docs/12 §2-3：论文实验
-- 数据随实现自动积累，禁止只留日志）。每次模型调用（clarify）与生成尝试
-- （generate）一行：模型、提示词版本、澄清轮次、重试次数、输出校验结果、
-- 错误码、耗时。不存提示词全文与模型原始输出（密钥/完整请求头绝不入库）。
CREATE TABLE IF NOT EXISTS ai_task_log (
    id             VARCHAR(64) PRIMARY KEY,
    issue_id       VARCHAR(64),
    kind           VARCHAR(20)  NOT NULL,
    model          VARCHAR(64)  NOT NULL,
    prompt_version VARCHAR(20)  NOT NULL,
    clarify_rounds INT          NOT NULL DEFAULT 1,
    retries        INT          NOT NULL DEFAULT 0,
    output_valid   BOOLEAN      NOT NULL,
    error_code     VARCHAR(64),
    duration_ms    BIGINT       NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_ai_task_kind CHECK (kind IN ('clarify', 'generate'))
);

CREATE INDEX IF NOT EXISTS idx_ai_task_log_issue ON ai_task_log (issue_id, created_at);
