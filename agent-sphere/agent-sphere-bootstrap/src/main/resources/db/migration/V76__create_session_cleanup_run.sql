-- ============================================================
-- V76：历史会话清理的任务执行记录
--
-- 手动触发与定时触发都往这里写一条，用于回答「谁在什么时候清理了什么、删了多少、失败没有」。
--
-- 为什么不用 sys_audit_log：那张表由 AuditLogCleanupTask 按 7/90 天自动清理，
-- 清理记录会自己消失，恰好丢掉了最需要长期留存的那部分信息。
--
-- status 取值刻意偏离通用字段约定的 'ACTIVE'：这是一次执行的生命周期，
-- 不是业务实体的状态，取值见 SessionCleanupRunStatusEnum。
-- ============================================================

CREATE TABLE IF NOT EXISTS agent_session_cleanup_run (
    id                      BIGSERIAL PRIMARY KEY,
    -- 触发方式：SCHEDULED（cron）/ MANUAL（管理端接口）
    trigger_type            VARCHAR(20)  NOT NULL,
    -- 预演还是真删：预演也留痕，但可据此区分「只看过」与「真删过」
    dry_run                 BOOLEAN      NOT NULL DEFAULT TRUE,
    status                  VARCHAR(20)  NOT NULL DEFAULT 'RUNNING',
    retention_days          INT,
    file_retention_days     INT,
    -- 过期判定线（运行开始时算出，便于事后核对当时的口径）
    cutoff                  TIMESTAMP,
    file_cutoff             TIMESTAMP,
    session_count           INT          NOT NULL DEFAULT 0,
    total_rows              BIGINT       NOT NULL DEFAULT 0,
    -- 因活跃守卫（PENDING/RUNNING 的 run 或 QUEUED/RUNNING 的 task）被放过的会话数
    skipped_active_sessions BIGINT       NOT NULL DEFAULT 0,
    batches                 INT          NOT NULL DEFAULT 0,
    -- 因达到单轮批次上限而提前结束，说明还有数据没清
    truncated               BOOLEAN      NOT NULL DEFAULT FALSE,
    elapsed_ms              BIGINT,
    -- 各表受影响行数（JSONB），UI 展开行直接展示
    table_stats             JSONB,
    -- 未执行的原因（锁被占用 / 急停开关关闭）
    skip_reason             VARCHAR(255),
    error_message           TEXT,
    started_at              TIMESTAMP    NOT NULL DEFAULT NOW(),
    finished_at             TIMESTAMP,
    -- 通用字段
    remark                  VARCHAR(500),
    delete_flag             SMALLINT     NOT NULL DEFAULT 0,
    created_by              VARCHAR(100),
    updated_by              VARCHAR(100),
    created_at              TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- 默认列表按开始时间倒序
CREATE INDEX IF NOT EXISTS idx_cleanup_run_started
    ON agent_session_cleanup_run (started_at DESC);

-- 供「记录本身也只保留 N 天」的自清理按时间扫描（V77 配置项驱动）
CREATE INDEX IF NOT EXISTS idx_cleanup_run_created
    ON agent_session_cleanup_run (created_at);