-- ============================================================
-- V81：skill 自动同步总开关与执行记录保留天数
--
-- 与会话清理同一套约定：运营/排障参数放系统配置表（改完不必发版），
-- 技术参数（间隔、批大小）留在 application.yml。
-- ============================================================

-- 急停开关：出问题时关掉自动同步，不必发版
INSERT INTO agent_system_config (config_group, config_key, config_value, is_secret, description)
VALUES ('skill', 'skill.auto-update-enabled', 'true', false,
        'Skill Hub 自动同步总开关（true=定时扫描并同步；false=只保留手动触发）')
ON CONFLICT (config_key) DO NOTHING;

-- 5 分钟一轮 × 30 天 ≈ 8640 行。行很小，不做清理也不影响，但留着会一直涨。
INSERT INTO agent_system_config (config_group, config_key, config_value, is_secret, description)
VALUES ('skill', 'skill.sync-log-retention-days', '30', false,
        'Skill 自动同步执行记录保留天数（capability_skill_sync_run；每轮扫描一行）')
ON CONFLICT (config_key) DO NOTHING;