-- 库存示例：插件自有物理台账表（docs/07 迁移对象须以插件短名前缀命名）。
-- 业务记录经 data_record 动态存储（docs/08 §5），本表承载演示用的插件自有对象
-- 与非负约束的数据库层兜底（应用层规则见 inventory-item.json qty min:0）。
CREATE TABLE IF NOT EXISTS example_inventory_item (
    id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sku   VARCHAR(64) NOT NULL,
    qty   INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_example_inventory_item_sku UNIQUE (sku),
    CONSTRAINT ck_example_inventory_item_qty_nonneg CHECK (qty >= 0)
);
