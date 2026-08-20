-- V001：平台骨架表（sys_*）。业务表禁止出现在平台迁移（ADR-0004、repository-maintenance §5）。
-- 幂等：全部使用 IF NOT EXISTS；时间统一 timestamptz/UTC（coding-standards §5）。
-- 内部主键 bigint，无对外业务 ID 字段（对外 ID 契约在 P02 冻结后再落列）。

CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(64)  NOT NULL DEFAULT '',
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_sys_user_username UNIQUE (username)
);

CREATE TABLE IF NOT EXISTS sys_role (
    id         BIGSERIAL PRIMARY KEY,
    code       VARCHAR(32) NOT NULL,
    name       VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_sys_role_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS sys_user_role (
    user_id BIGINT NOT NULL REFERENCES sys_user (id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES sys_role (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE INDEX IF NOT EXISTS idx_sys_user_status ON sys_user (status);

COMMENT ON TABLE sys_user IS '平台用户（骨架表，P03 启用认证）';
COMMENT ON TABLE sys_role IS '平台角色（管理员/开发者/普通用户，P03 启用）';
COMMENT ON TABLE sys_user_role IS '用户-角色关联（P03 启用）';
