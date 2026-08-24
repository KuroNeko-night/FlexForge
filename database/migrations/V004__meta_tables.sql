-- V004：元数据写模型平台表（service.meta 落地，P04；docs/03 §4）。
-- 实体/字段/视图定义存 meta_* 平台表；对外 ID 为不透明字符串（meta-<uuid>，写入端生成）。
-- 校验规则与视图列/筛选用 JSONB；字段类型白名单与 FieldTypeRegistry 同步
-- （DB CHECK 是存储层兜底，Java 侧唯一映射点在 FieldTypeRegistry，P04 验收以单点断言守护）。
-- 业务表迁移不在平台迁移内（ADR-0004）。幂等：全部 IF NOT EXISTS。

CREATE TABLE IF NOT EXISTS meta_entity (
    id           VARCHAR(64)  PRIMARY KEY,
    name         VARCHAR(64)  NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'draft',
    plugin_id    VARCHAR(128),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_meta_entity_name UNIQUE (name),
    CONSTRAINT ck_meta_entity_status CHECK (status IN ('draft', 'enabled', 'disabled'))
);

CREATE TABLE IF NOT EXISTS meta_field (
    id            VARCHAR(64)  PRIMARY KEY,
    entity_id     VARCHAR(64)  NOT NULL,
    name          VARCHAR(64)  NOT NULL,
    display_name  VARCHAR(100) NOT NULL,
    field_type    VARCHAR(32)  NOT NULL,
    required      BOOLEAN      NOT NULL DEFAULT false,
    default_value TEXT,
    validation    JSONB        NOT NULL DEFAULT '{}'::jsonb,
    renderer_id   VARCHAR(64),
    position      INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_meta_field_entity_name UNIQUE (entity_id, name),
    CONSTRAINT fk_meta_field_entity FOREIGN KEY (entity_id) REFERENCES meta_entity (id),
    CONSTRAINT ck_meta_field_type CHECK (field_type IN ('text', 'integer', 'decimal', 'date', 'enum', 'boolean'))
);

CREATE TABLE IF NOT EXISTS meta_view (
    id         VARCHAR(64)  PRIMARY KEY,
    entity_id  VARCHAR(64)  NOT NULL,
    view_type  VARCHAR(16)  NOT NULL,
    name       VARCHAR(100) NOT NULL,
    columns    JSONB        NOT NULL DEFAULT '[]'::jsonb,
    filters    JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_meta_view_entity_type UNIQUE (entity_id, view_type),
    CONSTRAINT fk_meta_view_entity FOREIGN KEY (entity_id) REFERENCES meta_entity (id),
    CONSTRAINT ck_meta_view_type CHECK (view_type IN ('list', 'form'))
);

CREATE INDEX IF NOT EXISTS idx_meta_entity_status ON meta_entity (status);
CREATE INDEX IF NOT EXISTS idx_meta_field_entity ON meta_field (entity_id);
CREATE INDEX IF NOT EXISTS idx_meta_view_entity ON meta_view (entity_id);

COMMENT ON TABLE meta_entity IS '实体定义（FR-META-01：draft/enabled/disabled；plugin_id 为插件来源，NULL=平台配置）';
COMMENT ON TABLE meta_field IS '字段定义（FR-META-02：六类白名单；validation 按类型键白名单，default_value 为 JSON 标量文本）';
COMMENT ON TABLE meta_view IS '视图配置（FR-META-03：columns 按数组序展示 [{field,visible}]，filters 仅 list [{field,operator}]）';
