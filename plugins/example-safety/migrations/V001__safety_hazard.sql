-- 安全隐患示例：插件自有隐患台账（docs/07 迁移对象须以插件短名前缀命名）。
-- 业务记录经 data_record 动态存储（docs/08 §5），本表承载插件自有对象与
-- 等级/整改状态取值的数据库层兜底（应用层枚举规则见 safety-hazard.json options）。
CREATE TABLE IF NOT EXISTS example_safety_hazard (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    summary      VARCHAR(160) NOT NULL,
    location     VARCHAR(80)  NOT NULL,
    level        VARCHAR(16)  NOT NULL,
    status       VARCHAR(16)  NOT NULL,
    CONSTRAINT uq_example_safety_hazard UNIQUE (summary),
    CONSTRAINT ck_example_safety_hazard_level
        CHECK (level IN ('一般', '较大', '重大')),
    CONSTRAINT ck_example_safety_hazard_status
        CHECK (status IN ('待整改', '整改中', '已闭环'))
);
