package com.buukle.agent.instance.dtvo.enums;

/**
 * 会话清理任务的触发方式（{@code agent_session_cleanup_run.trigger_type}）。
 *
 * <p>两者都要留痕：定时任务是常规运维动作，手动触发往往对应「磁盘告警要立刻处理」，
 * 事后需要区分是谁在什么时候按的。
 */
public final class SessionCleanupTriggerEnum {

    /** cron 定时触发 */
    public static final String TRIGGER_SCHEDULED = "SCHEDULED";
    /** 管理端接口手动触发 */
    public static final String TRIGGER_MANUAL = "MANUAL";

    private SessionCleanupTriggerEnum() {
    }
}