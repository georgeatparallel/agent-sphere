package com.buukle.agent.instance.dtvo.enums;

/**
 * 会话清理任务的执行状态（{@code agent_session_cleanup_run.status}）。
 *
 * <p>刻意不用通用字段约定的 {@code 'ACTIVE'}：这是一次执行的生命周期，不是业务实体状态。
 */
public final class SessionCleanupRunStatusEnum {

    /** 已开始，尚未落终态。若长时间停在这里（见 {@code stale} 判定）说明进程崩了 */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 正常结束（含预演） */
    public static final String STATUS_SUCCESS = "SUCCESS";
    /** 执行中抛异常 */
    public static final String STATUS_FAILED = "FAILED";
    /** 什么都没做：锁被别的副本/请求占用，或急停开关关闭 */
    public static final String STATUS_SKIPPED = "SKIPPED";

    private SessionCleanupRunStatusEnum() {
    }
}