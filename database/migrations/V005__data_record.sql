-- V005：动态记录存储（service.data-access 落地，P05；docs/03 §4 存储定案）。
-- 单 JSONB 记录表：所有动态实体共用，data 为 {字段名: 值}；字段名/类型/规则
-- 由 meta_field + FieldTypeRegistry 白名单在 API 边界校验，SQL 构造仅使用
-- 已校验标识符与固定类型转换（NFR-SEC-02：无用户输入拼接）。
-- 幂等：IF NOT EXISTS。

CREATE TABLE IF NOT EXISTS data_record (
    id         VARCHAR(64)  PRIMARY KEY,
    entity_id  VARCHAR(64)  NOT NULL,
    data       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_data_record_entity FOREIGN KEY (entity_id) REFERENCES meta_entity (id)
);

CREATE INDEX IF NOT EXISTS idx_data_record_entity ON data_record (entity_id);
-- 过滤路径加速（等值/包含）；范围过滤走 (data->>'f')::type 表达式，演示数据量下足够
CREATE INDEX IF NOT EXISTS idx_data_record_data ON data_record USING GIN (data jsonb_path_ops);

COMMENT ON TABLE data_record IS '动态实体记录（P05；物理删除+审计，MVP 无软删除，docs/09 P05）';
