package com.buukle.agent.capability.skill.dtvo.enums;

/**
 * Skill 自动同步的触发方式（{@code capability_skill_sync_run.trigger_type}）。
 */
public final class SkillSyncTriggerEnum {

    /** 定时扫描（每 {@code buukle.agent.skill.auto-update-interval} 一轮） */
    public static final String TRIGGER_SCHEDULED = "SCHEDULED";
    /** 管理端手动点「检查更新」 */
    public static final String TRIGGER_MANUAL = "MANUAL";

    private SkillSyncTriggerEnum() {
    }
}