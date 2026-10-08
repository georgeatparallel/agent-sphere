-- ============================================================
-- V79：skill 安装副本的同步元信息
--
-- 为什么要新增两个字段，而不是直接复用已有的 origin_version / updated_at：
--
--   origin_version —— 同步判定的乐观锁字段，会被条件 UPDATE 反复读改写；
--                    一旦条件更新失败（影响 0 行），它与界面显示会脱节。
--   updated_at     —— 被用户编辑、开关自动更新、状态变更等同一条记录共用，
--                    语义不可区分，无法回答「什么时候同步的」。
--
-- 这两个字段是纯展示快照：只在 install（fork 时）与 syncFromOrigin（同步成功后）写入，
-- 取值与 origin_version 始终一致但互不影响。
-- ============================================================

-- 最后一次与源头对齐的时间。install 时记为安装时间，syncFromOrigin 成功时刷新。
ALTER TABLE capability_skill
    ADD COLUMN IF NOT EXISTS synced_at TIMESTAMP;

-- 最后一次同步到的源头版本号（快照，只读展示用）
ALTER TABLE capability_skill
    ADD COLUMN IF NOT EXISTS synced_from_version INT;

-- 详情页/列表要按「已同步时间」排序排查陈旧副本时可用
CREATE INDEX IF NOT EXISTS idx_skill_synced_at
    ON capability_skill (synced_at);