-- V014：插件预设（P21，FR-PLUGIN-12，docs/07 §1）：ADMIN 保存当前启用插件集合
-- 快照（[{pluginId,versionId,version}]），应用=收敛（停用预设外→按预设切换/激活），
-- 编排复用既有生命周期（审计/占用/幂等不变），本表只存快照与创建者。
-- 幂等：IF NOT EXISTS；名称唯一（应用层先拒绝重名，此处兜底并发窗口）。

CREATE TABLE IF NOT EXISTS plugin_preset (
    id         VARCHAR(64)  PRIMARY KEY,
    name       VARCHAR(50)  NOT NULL,
    payload    JSONB        NOT NULL,
    created_by VARCHAR(64)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_plugin_preset_name UNIQUE (name)
);

COMMENT ON TABLE plugin_preset IS '插件预设（P21）：启用集合快照，应用=收敛到保存时的插件与版本';
