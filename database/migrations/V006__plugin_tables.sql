-- V006：插件版本存储（P07；docs/07 §1 持久化对象）。
-- plugin_instance/plugin_version/plugin_dependency 本期读写；plugin_activation/
-- plugin_registration 属 P08 生命周期；plugin_migration 本期建表但不写入
-- （activation_id 留空，P08 安装期由迁移 runner 写入，ADR-0005）。
-- 版本不可变：content_hash 全库唯一（幂等导入），(plugin_id, version) 唯一
-- （同版本异内容导入在应用层先拒绝）。幂等：IF NOT EXISTS。

CREATE TABLE IF NOT EXISTS plugin_instance (
    id          VARCHAR(64)  PRIMARY KEY,
    plugin_id   VARCHAR(128) NOT NULL,
    name        VARCHAR(100) NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'imported',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_plugin_instance_plugin_id UNIQUE (plugin_id)
);

CREATE TABLE IF NOT EXISTS plugin_version (
    id               VARCHAR(64) PRIMARY KEY,
    plugin_id        VARCHAR(128) NOT NULL,
    version          VARCHAR(32)  NOT NULL,
    content_hash     VARCHAR(64)  NOT NULL,
    capability_level SMALLINT     NOT NULL,
    manifest_json    JSONB        NOT NULL,
    script_checksums JSONB        NOT NULL DEFAULT '{}'::jsonb,
    size_bytes       BIGINT       NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_plugin_version_hash UNIQUE (content_hash),
    CONSTRAINT uq_plugin_version_natural UNIQUE (plugin_id, version),
    CONSTRAINT fk_plugin_version_instance
        FOREIGN KEY (plugin_id) REFERENCES plugin_instance (plugin_id)
);

CREATE TABLE IF NOT EXISTS plugin_dependency (
    id                VARCHAR(64) PRIMARY KEY,
    plugin_version_id VARCHAR(64) NOT NULL,
    dependency_id     VARCHAR(128) NOT NULL,
    version_range     VARCHAR(64) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_plugin_dependency UNIQUE (plugin_version_id, dependency_id),
    CONSTRAINT fk_plugin_dependency_version
        FOREIGN KEY (plugin_version_id) REFERENCES plugin_version (id)
);

CREATE TABLE IF NOT EXISTS plugin_migration (
    id                VARCHAR(64) PRIMARY KEY,
    plugin_version_id VARCHAR(64) NOT NULL,
    activation_id     VARCHAR(64),
    script_name       VARCHAR(128) NOT NULL,
    checksum          VARCHAR(64) NOT NULL,
    applied_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_plugin_migration_script UNIQUE (plugin_version_id, script_name),
    CONSTRAINT fk_plugin_migration_version
        FOREIGN KEY (plugin_version_id) REFERENCES plugin_version (id)
);

CREATE INDEX IF NOT EXISTS idx_plugin_version_plugin ON plugin_version (plugin_id);

COMMENT ON TABLE plugin_instance IS '插件实例（稳定 plugin_id；status 生命周期 P08 接管，本期 imported）';
COMMENT ON TABLE plugin_version IS '不可变插件包版本（manifest 与 content_hash 幂等导入；script_checksums 导入期计算）';
COMMENT ON TABLE plugin_dependency IS '版本依赖（导入期解析并存储；缺失在导入时拒绝）';
COMMENT ON TABLE plugin_migration IS '插件迁移记录（ADR-0005：P08 安装期由 runner 写入，非 Flyway）';
