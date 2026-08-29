-- P10：Issue 与版本化规格（docs/03 §7、docs/09 P10、FR-ISSUE-01..04/06）。
-- 状态机：SUBMITTED→APPROVED|RETURNED；APPROVED→IN_TESTING|DEV_FAILED；
-- IN_TESTING→TESTED|FEEDBACK；TESTED→DONE|CLOSED；迭代回退：
-- RETURNED/FEEDBACK/DEV_FAILED→（SUBMITTED/APPROVED，docs/03 §7 2026-08-29 补记）。
CREATE TABLE IF NOT EXISTS issue (
    id          VARCHAR(64)  PRIMARY KEY,
    title       VARCHAR(120) NOT NULL,
    description TEXT         NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    created_by  VARCHAR(64)  NOT NULL,
    assigned_to VARCHAR(64),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_issue_status CHECK (status IN
        ('SUBMITTED', 'APPROVED', 'RETURNED', 'IN_TESTING', 'DEV_FAILED',
         'TESTED', 'FEEDBACK', 'DONE', 'CLOSED'))
);

CREATE INDEX IF NOT EXISTS idx_issue_status ON issue (status);

CREATE TABLE IF NOT EXISTS issue_label (
    issue_id VARCHAR(64) NOT NULL REFERENCES issue (id) ON DELETE CASCADE,
    label    VARCHAR(40) NOT NULL,
    CONSTRAINT pk_issue_label PRIMARY KEY (issue_id, label)
);

CREATE TABLE IF NOT EXISTS issue_comment (
    id         VARCHAR(64) PRIMARY KEY,
    issue_id   VARCHAR(64) NOT NULL REFERENCES issue (id) ON DELETE CASCADE,
    author     VARCHAR(64) NOT NULL,
    body       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_issue_comment_issue ON issue_comment (issue_id, created_at);

-- 状态迁移记录（docs/03 §7：领域服务统一执行，记操作者/原因/时间）
CREATE TABLE IF NOT EXISTS issue_transition (
    id           VARCHAR(64) PRIMARY KEY,
    issue_id     VARCHAR(64) NOT NULL REFERENCES issue (id) ON DELETE CASCADE,
    from_status  VARCHAR(20) NOT NULL,
    to_status    VARCHAR(20) NOT NULL,
    operator     VARCHAR(64) NOT NULL,
    reason       VARCHAR(500),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_issue_transition_issue ON issue_transition (issue_id, created_at);

-- 版本化规格（FR-ISSUE-04：AI 输出符合版本化 JSON Schema；每保存一次新增一版，
-- valid + 校验错误快照随版本审计；批准门=最新版 valid）
CREATE TABLE IF NOT EXISTS requirement_spec (
    id                VARCHAR(64) PRIMARY KEY,
    issue_id          VARCHAR(64) NOT NULL REFERENCES issue (id) ON DELETE CASCADE,
    schema_version    INT          NOT NULL,
    revision          INT          NOT NULL,
    spec_json         JSONB        NOT NULL,
    valid             BOOLEAN      NOT NULL,
    validation_errors JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_by        VARCHAR(64)  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_requirement_spec_revision UNIQUE (issue_id, revision)
);
