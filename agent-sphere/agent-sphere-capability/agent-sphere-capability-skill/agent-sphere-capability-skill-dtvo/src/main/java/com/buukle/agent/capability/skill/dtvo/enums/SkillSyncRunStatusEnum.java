package com.buukle.agent.capability.skill.dtvo.enums;

/**
 * Skill 自动同步的执行状态（{@code capability_skill_sync_run.status}）。
 *
 * <p>与 {@code SessionCleanupRunStatusEnum} 同一套形状，两处任务的前端轮询面板得以复用同一逻辑。
 */
public final class SkillSyncRunStatusEnum {

    /** 已开始，尚未落终态；长时间停在这里（见 stale）说明进程崩了 */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 正常结束 */
    public static final String STATUS_SUCCESS = "SUCCESS";
    /** 什么都没做：锁被占用或总开关关闭 */
    public static final String STATUS_SKIPPED = "SKIPPED";
    /** 执行中抛异常 */
    public static final String STATUS_FAILED = "FAILED";

    private SkillSyncRunStatusEnum() {
    }
}
