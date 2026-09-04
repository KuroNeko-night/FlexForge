-- 生产工单示例：种子数据（可重复脚本——ON CONFLICT DO NOTHING，重复安装不重复插入）。
INSERT INTO example_production_order (code, product, plan_qty, status) VALUES
    ('WO-2609-001', '精密主轴组件', 40, '执行中'),
    ('WO-2609-002', '伺服驱动外壳', 200, '计划'),
    ('WO-2609-003', '传动齿轮箱', 60, '暂停'),
    ('WO-2608-012', '数控工作台', 15, '完工')
ON CONFLICT (code) DO NOTHING;
