-- 采购订单示例：插件自有采购台账（docs/07 迁移对象须以插件短名前缀命名）。
-- 业务记录经 data_record 动态存储（docs/08 §5），本表承载插件自有对象与
-- 状态取值的数据库层兜底（应用层枚举规则见 purchase-order.json status options）；
-- 金额精度与应用层 NUMERIC(20,6) 契约一致（FieldTypeRegistry DECIMAL）。
CREATE TABLE IF NOT EXISTS example_purchase_order (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code       VARCHAR(40)    NOT NULL,
    supplier   VARCHAR(80)    NOT NULL,
    material   VARCHAR(80)    NOT NULL,
    qty        BIGINT         NOT NULL,
    amount     NUMERIC(20, 6) NOT NULL,
    status     VARCHAR(16)    NOT NULL,
    CONSTRAINT uq_example_purchase_order_code UNIQUE (code),
    CONSTRAINT ck_example_purchase_order_status
        CHECK (status IN ('待审批', '已下单', '部分到货', '已完结')),
    CONSTRAINT ck_example_purchase_order_qty CHECK (qty > 0),
    CONSTRAINT ck_example_purchase_order_amount CHECK (amount >= 0)
);
