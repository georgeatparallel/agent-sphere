-- ============================================================
-- agent_task 任务级超时时间
--
-- 背景：原先 AS 侧任务超时是硬编码的 60 分钟兜底。现在允许业务方（Bole）在创建任务时指定，
-- 未指定则由 AS 侧配置 hri-ai.tasks.max-poll-seconds 兜底。
--
-- 为什么要落库：该值不仅决定任务存活上限（pollOnce 超时判 FAILED），
-- 也是业务方签发 MCP 任务凭证的 TTL 依据（凭证 TTL = 任务超时 + 5 分钟余量）。
-- 两侧必须用同一个数，否则会出现「任务还在跑但凭证已过期」。
--
-- 历史任务该列为 NULL，读取时回落到 AS 侧配置默认值。
-- ============================================================
ALTER TABLE agent_task
    ADD COLUMN IF NOT EXISTS task_timeout_seconds INTEGER NULL;

COMMENT ON COLUMN agent_task.task_timeout_seconds IS '任务超时秒数；NULL 表示使用 AS 侧配置默认值';
