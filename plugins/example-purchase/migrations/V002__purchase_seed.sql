-- 采购订单示例：种子数据（可重复脚本——ON CONFLICT DO NOTHING，重复安装不重复插入）。
INSERT INTO example_purchase_order (code, supplier, material, qty, amount, status) VALUES
    ('PO-2609-001', '华东精密配件', '轴承 6204-2RS', 2000, 18600.00, '部分到货'),
    ('PO-2609-002', '南方铝业', '铝型材 4040', 800, 47200.50, '已下单'),
    ('PO-2610-003', '华北橡塑', '密封圈 NBR70', 5000, 3250.00, '待审批')
ON CONFLICT (code) DO NOTHING;
