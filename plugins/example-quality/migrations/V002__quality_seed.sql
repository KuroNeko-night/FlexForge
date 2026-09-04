-- 来料检验示例：种子数据（可重复脚本——ON CONFLICT DO NOTHING，重复安装不重复插入；
-- 冲突目标与唯一键一致（material+sample_qty），supplier 可空不入键避免 NULLS DISTINCT 语义差）。
INSERT INTO example_quality_inspection (material, supplier, sample_qty, defect_qty, verdict) VALUES
    ('轴承 6204-2RS', '华东精密配件', 125, 0, '合格'),
    ('铝型材 4040', '南方铝业', 200, 3, '让步接收'),
    ('密封圈 NBR70', '华北橡塑', 80, 12, '不合格')
ON CONFLICT (material, sample_qty) DO NOTHING;
