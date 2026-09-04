-- 安全隐患示例：种子数据（可重复脚本——ON CONFLICT DO NOTHING，重复安装不重复插入）。
INSERT INTO example_safety_hazard (summary, location, level, status) VALUES
    ('冲压车间防护栅栏松动', '冲压车间 A 线', '较大', '整改中'),
    ('化学品仓库通风扇异响', '原料仓库二层', '一般', '待整改'),
    ('配电柜接地线老化', '装配车间西侧', '重大', '待整改')
ON CONFLICT (summary) DO NOTHING;
