-- 设备巡检示例：插件自有点检物理台账（docs/07 迁移对象须以插件短名前缀命名）。
-- 业务记录经 data_record 动态存储（docs/08 §5），本表承载插件自有对象与
-- 停机时长的数据库层兜底（应用层规则见 facility-check.json downtime_minutes min:0）。
CREATE TABLE IF NOT EXISTS example_facility_check (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device           VARCHAR(80) NOT NULL,
    downtime_minutes INTEGER,
    CONSTRAINT ck_example_facility_check_downtime_nonneg
        CHECK (downtime_minutes IS NULL OR downtime_minutes >= 0)
);
