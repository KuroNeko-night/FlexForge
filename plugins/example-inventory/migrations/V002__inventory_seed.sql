-- 库存示例：种子数据（可重复脚本——ON CONFLICT DO NOTHING，重复安装不重复插入）。
INSERT INTO example_inventory_item (sku, qty) VALUES
    ('DEMO-001', 120),
    ('DEMO-002', 40),
    ('DEMO-003', 0)
ON CONFLICT (sku) DO NOTHING;
