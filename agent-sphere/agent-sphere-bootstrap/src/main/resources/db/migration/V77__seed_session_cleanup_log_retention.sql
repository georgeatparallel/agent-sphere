-- ============================================================
-- V77：清理任务自身执行记录的保留天数
--
-- 记录表本身也会随时间增长（本任务每次执行写一条）。纳入本任务最后一个阶段清理，
-- 避免「清理历史数据」的机制自己变成新的垃圾。
-- ============================================================

INSERT INTO agent_system_config (config_group, config_key, config_value, is_secret, description)
VALUES ('session', 'session.cleanup-log-retention-days', '90', false,
        '会话清理执行记录保留天数（agent_session_cleanup_run；每天至多一条自动记录，量很小，但会无限增长）')
ON CONFLICT (config_key) DO NOTHING;