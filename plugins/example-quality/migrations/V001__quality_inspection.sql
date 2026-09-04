-- 来料检验示例：插件自有检验台账（docs/07 迁移对象须以插件短名前缀命名）。
-- 业务记录经 data_record 动态存储（docs/08 §5），本表承载插件自有对象与
-- 判定取值/数量下界的数据库层兜底（应用层规则见 quality-inspection.json）。
CREATE TABLE IF NOT EXISTS example_quality_inspection (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    material   VARCHAR(80) NOT NULL,
    supplier   VARCHAR(80),
    sample_qty BIGINT      NOT NULL,
    defect_qty BIGINT      NOT NULL,
    verdict    VARCHAR(16) NOT NULL,
    CONSTRAINT uq_example_quality_inspection UNIQUE (material, sample_qty),
    CONSTRAINT ck_example_quality_verdict
        CHECK (verdict IN ('合格', '让步接收', '不合格')),
    CONSTRAINT ck_example_quality_sample_qty CHECK (sample_qty >= 1),
    CONSTRAINT ck_example_quality_defect_qty CHECK (defect_qty >= 0)
);
