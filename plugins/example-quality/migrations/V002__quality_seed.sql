-- 来料检验示例：种子数据（可重复脚本——ON CONFLICT DO NOTHING，重复安装不重复插入）。
-- 无冲突目标形式：0.1.0 已装环境的表唯一键为 (material, supplier, sample_qty)，
-- 0.1.1+ 表定义为 (material, sample_qty)——显式目标会与既有表失配致激活失败
-- （live 实测 migration_failed），无目标形式对两种表形态均幂等。
INSERT INTO example_quality_inspection (material, supplier, sample_qty, defect_qty, verdict) VALUES
    ('轴承 6204-2RS', '华东精密配件', 125, 0, '合格'),
    ('铝型材 4040', '南方铝业', 200, 3, '让步接收'),
    ('密封圈 NBR70', '华北橡塑', 80, 12, '不合格')
ON CONFLICT DO NOTHING;
