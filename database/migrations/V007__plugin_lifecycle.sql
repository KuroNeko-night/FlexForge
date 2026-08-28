-- V007：插件生命周期（P08；docs/07 §1 持久化对象）。
-- plugin_version 补列（V006 建表时未存包内文件内容，P08 生命周期需要）；
-- plugin_migration/plugin_audit_event V006 已建（migration）/本期补建（audit_event）。

-- 版本表补列：脚本内容（迁移执行）与资源载荷（实体/视图定义，注册编排用）
ALTER TABLE plugin_version ADD COLUMN IF NOT EXISTS script_payloads JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE plugin_version ADD COLUMN IF NOT EXISTS resource_payloads JSONB NOT NULL DEFAULT '{}'::jsonb;

-- plugin_activation：一次安装/启停/升级尝试（状态机 + 失败阶段 + 稳定错误码）；
-- plugin_registration：激活期间产生的注册记录（停用/卸载按 activationId 清理依据）。
-- 幂等：IF NOT EXISTS。

CREATE TABLE IF NOT EXISTS plugin_activation (
    id                VARCHAR(64) PRIMARY KEY,
    plugin_id         VARCHAR(128) NOT NULL,
    plugin_version_id VARCHAR(64)  NOT NULL,
    operation         VARCHAR(16)  NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    stage             VARCHAR(32),
    error_code        VARCHAR(64),
    requested_by      VARCHAR(64)  NOT NULL,
    started_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at       TIMESTAMPTZ,
    CONSTRAINT fk_plugin_activation_version
        FOREIGN KEY (plugin_version_id) REFERENCES plugin_version (id)
);

CREATE INDEX IF NOT EXISTS idx_plugin_activation_plugin
    ON plugin_activation (plugin_id, status);
CREATE INDEX IF NOT EXISTS idx_plugin_activation_version
    ON plugin_activation (plugin_version_id, status);

CREATE TABLE IF NOT EXISTS plugin_registration (
    id                VARCHAR(64) PRIMARY KEY,
    activation_id     VARCHAR(64) NOT NULL,
    extension_type    VARCHAR(64) NOT NULL,
    registration_key  VARCHAR(128) NOT NULL,
    payload_json      JSONB NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_plugin_registration_activation
        FOREIGN KEY (activation_id) REFERENCES plugin_activation (id),
    CONSTRAINT uq_plugin_registration UNIQUE (activation_id, extension_type, registration_key)
);

CREATE TABLE IF NOT EXISTS plugin_audit_event (
    id            VARCHAR(64) PRIMARY KEY,
    plugin_id     VARCHAR(128) NOT NULL,
    activation_id VARCHAR(64),
    event_type    VARCHAR(64) NOT NULL,
    payload_json  JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_plugin_audit_event_plugin ON plugin_audit_event (plugin_id, occurred_at DESC);

COMMENT ON TABLE plugin_activation IS '插件激活尝试（状态机 docs/07 §3-§4：STARTING/ACTIVE/STOPPING/STOPPED/FAILED+失败阶段）';
COMMENT ON TABLE plugin_registration IS '激活期间注册记录（停用/卸载按 activationId 清理；重启恢复依据）';
COMMENT ON TABLE plugin_audit_event IS '插件领域追加事件（docs/07 §1；区别于 sys_audit_event 的平台审计）';
