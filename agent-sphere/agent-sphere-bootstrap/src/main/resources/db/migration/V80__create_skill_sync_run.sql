-- ============================================================
-- V80：skill 自动同步的执行记录
--
-- 每轮扫描写一行（定时 5 分钟一轮）。它同时是「任务到底跑没跑」的唯一证据：
-- SkillAutoUpdateSweeper 的 logger 被 logback 的
-- com.buukle.agent.capability=WARN 规则压住，log.info 永不落盘，
-- 只靠日志无法判断任务是否执行。
--
-- 形状与 agent_session_cleanup_run 保持一致（trigger_type / status / started_at /
-- finished_at / skip_reason / error_message），便于两处共用同一套前端轮询组件与
-- AbstractRecordedTask 基类。
-- ============================================================

CREATE TABLE IF NOT EXISTS capability_skill_sync_run (
    id             BIGSERIAL PRIMARY KEY,
    -- SCHEDULED（定时）/ MANUAL（手动点「检查更新」）
    trigger_type   VARCHAR(20)   NOT NULL,
    status         VARCHAR(20)   NOT NULL DEFAULT 'RUNNING',
    -- 本轮扫描到的副本总数 / 实际同步数 / 跳过数
    scanned_count  INT           NOT NULL DEFAULT 0,
    handled        INT           NOT NULL DEFAULT 0,
    updated        INT           NOT NULL DEFAULT 0,
    skipped        INT           NOT NULL DEFAULT 0,
    batch_size     INT,
    -- 跳过的明细 [{copyId, originId, reason}]，用来回答「某个技能为什么没同步」
    detail         JSONB,
    -- 未执行的原因（锁被占用 / 同步总开关关闭）
    skip_reason    VARCHAR(255),
    error_message  TEXT,
    elapsed_ms     BIGINT,
    started_at     TIMESTAMP     NOT NULL DEFAULT NOW(),
    finished_at    TIMESTAMP,
    -- 通用字段
    remark         VARCHAR(500),
    delete_flag    SMALLINT      NOT NULL DEFAULT 0,
    created_by     VARCHAR(100),
    updated_by     VARCHAR(100),
    created_at     TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP     NOT NULL DEFAULT NOW()
);

-- 默认列表按开始时间倒序
CREATE INDEX IF NOT EXISTS idx_skill_sync_run_started
    ON capability_skill_sync_run (started_at DESC);

-- 供「记录本身也只保留 N 天」的自清理按时间扫描
CREATE INDEX IF NOT EXISTS idx_skill_sync_run_created
    ON capability_skill_sync_run (created_at);