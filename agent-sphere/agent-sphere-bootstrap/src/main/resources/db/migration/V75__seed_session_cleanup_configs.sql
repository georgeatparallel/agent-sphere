-- ============================================================
-- V75：历史会话清理任务配置项
--
-- 清理策略属于「运营参数」：各环境差异大（本地想留 1 天、生产可能 90 天），
-- 且改完要求不重启生效，故存系统配置表而非 application.yml。
-- 调度表达式/批大小等技术参数仍留在 yml（buukle.agent.session.cleanup-*），
-- 改它们意味着发版；改下面三项只需在系统配置页改，立即生效（Redis 缓存 5 分钟内）。
-- ============================================================

-- 急停开关：清理是不可逆的硬删，出问题先关这个，不必发版
INSERT INTO agent_system_config (config_group, config_key, config_value, is_secret, description)
VALUES ('session', 'session.cleanup-enabled', 'true', false,
        '历史会话清理开关（true=启用定时清理；清理为硬删不可逆，出问题先关此项）')
ON CONFLICT (config_key) DO NOTHING;

-- session / task / completions 的保留天数
INSERT INTO agent_system_config (config_group, config_key, config_value, is_secret, description)
VALUES ('session', 'session.cleanup-retention-days', '7', false,
        '会话数据保留天数：created_at 与 updated_at 均早于该天数的 session 及其关联数据会被清理（默认 7）')
ON CONFLICT (config_key) DO NOTHING;

-- 截图/附件保留天数（体积远大于会话文本，单独配置）
INSERT INTO agent_system_config (config_group, config_key, config_value, is_secret, description)
VALUES ('session', 'session.file-retention-days', '7', false,
        '截图与聊天附件保留天数（agent_file_store；单张截图上限 8MB，是磁盘占用大头，默认 7）')
ON CONFLICT (config_key) DO NOTHING;
