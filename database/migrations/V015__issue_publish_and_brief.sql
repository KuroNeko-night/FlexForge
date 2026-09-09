-- P23：Issue 确认推送与三段式简报（FR-ISSUE-03B/07，docs/09 P23）。
-- brief_json：提示词 v3 信息足够时随规格版本落库的三段简报
--（colloquial=口语化确认→用户；feasibility=可行性→开发者；agentPrompt=制作提示词→开发者/agent）。
-- 既有版本行 brief_json 为 NULL（v2 及更早无简报，publish 门条件要求最新版本齐备）。
ALTER TABLE requirement_spec
    ADD COLUMN IF NOT EXISTS brief_json JSONB;

-- published_at：用户确认并推送后台的时间（NULL=未发布；幂等重入不覆盖）。
ALTER TABLE issue
    ADD COLUMN IF NOT EXISTS published_at TIMESTAMPTZ;
