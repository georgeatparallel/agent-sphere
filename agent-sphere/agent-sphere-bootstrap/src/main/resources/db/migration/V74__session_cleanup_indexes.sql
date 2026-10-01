-- ============================================================
-- V74：历史会话清理所需索引
--
-- 清理任务（SessionCleanupTask）按「时间筛 agent_session → 拿 session id 集合 →
-- 按 session_id 级联删子表」的策略执行，因此需要两类索引：
--   1) 驱动表 agent_session 的时间过滤（原先只有 tenant/instance 索引，按时间筛必然全表扫）
--   2) 无索引表的 session_id / created_at（agent_task 建表时零索引，agent_file_store、
--      agent_completions_call 同样没有 created_at 索引）
-- 子表（run/timeline/tool_call/...）已有 session_id 索引，本迁移不重复添加。
--
-- 注：清理走硬删（DELETE），必须能按 session_id 命中索引，否则会退化成全表扫描 + 长事务。
-- ============================================================

-- 驱动表：过期会话筛选（配合 delete_flag = 0 AND created_at < ? AND updated_at < ?）
CREATE INDEX IF NOT EXISTS idx_session_created
    ON agent_session (delete_flag, created_at);

-- agent_task 原先零索引：级联删与活跃任务守卫都依赖 session_id
CREATE INDEX IF NOT EXISTS idx_task_session
    ON agent_task (session_id);

-- agent_task 孤儿行（session_id 为空/悬空）按时间分批清理
CREATE INDEX IF NOT EXISTS idx_task_created
    ON agent_task (delete_flag, created_at);

-- 截图/附件（BYTEA，磁盘占用最大）按 biz_key + 时间清理
CREATE INDEX IF NOT EXISTS idx_file_store_created
    ON agent_file_store (biz_key, created_at);

-- completions 调用日志（纯追加型，原先零索引）
CREATE INDEX IF NOT EXISTS idx_completions_call_created
    ON agent_completions_call (created_at);
