-- 生产工单示例：插件自有工单台账（docs/07 迁移对象须以插件短名前缀命名）。
-- 业务记录经 data_record 动态存储（docs/08 §5），本表承载插件自有对象与
-- 状态取值的数据库层兜底（应用层枚举规则见 production-order.json status options）。
CREATE TABLE IF NOT EXISTS example_production_order (
    id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code     VARCHAR(40) NOT NULL,
    product  VARCHAR(80) NOT NULL,
    plan_qty BIGINT      NOT NULL,
    status   VARCHAR(16) NOT NULL,
    CONSTRAINT uq_example_production_order_code UNIQUE (code),
    CONSTRAINT ck_example_production_order_status
        CHECK (status IN ('计划', '执行中', '暂停', '完工'))
);
