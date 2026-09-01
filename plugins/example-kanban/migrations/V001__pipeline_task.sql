-- 任务管线看板示例：插件自有管线物理台账（docs/07 迁移对象须以插件短名前缀命名）。
-- 业务记录经 data_record 动态存储（docs/08 §5），本表承载插件自有对象与
-- 阶段取值的数据库层兜底（应用层枚举规则见 pipeline-task.json stage options）。
CREATE TABLE IF NOT EXISTS example_pipeline_task (
    id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title VARCHAR(120) NOT NULL,
    stage VARCHAR(16)  NOT NULL,
    CONSTRAINT uq_example_pipeline_task_title UNIQUE (title),
    CONSTRAINT ck_example_pipeline_task_stage
        CHECK (stage IN ('待办', '进行中', '已完成'))
);
