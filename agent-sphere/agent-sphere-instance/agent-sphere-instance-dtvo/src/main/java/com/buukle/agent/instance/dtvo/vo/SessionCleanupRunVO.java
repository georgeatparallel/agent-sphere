package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 会话清理的执行记录（{@code agent_session_cleanup_run} 一行）。
 *
 * <p>预演（{@code dryRun=true}）同样留痕：清理不可逆，「当时预演过什么、看到多少行」
 * 是事后复盘的关键线索，用本字段区分即可。
 */
@Data
public class SessionCleanupRunVO implements Serializable {

    private Long id;

    /** SCHEDULED / MANUAL */
    private String triggerType;

    /** true=只统计未删除；false=真实硬删 */
    private boolean dryRun;

    /** RUNNING / SUCCESS / FAILED / SKIPPED */
    private String status;

    private Integer retentionDays;

    private Integer fileRetentionDays;

    private LocalDateTime cutoff;

    private LocalDateTime fileCutoff;

    private Integer sessionCount;

    /** 进度分母：执行前 count 出的过期会话总数；前端据此算百分比 */
    private Integer totalSessions;

    private Long totalRows;

    private Long skippedActiveSessions;

    private Integer batches;

    private Boolean truncated;

    private Long elapsedMs;

    /** 各表受影响行数 */
    private Map<String, Long> tableStats;

    private String skipReason;

    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    /**
     * 疑似中断：进程崩了导致记录永远停在 RUNNING。
     * 由 SQL 按 {@code finished_at IS NULL AND started_at < now() - 30min} 判定，
     * 避免运维把一条僵尸记录误读成「正在清理」。
     */
    private Boolean stale;

    private String createdBy;

    /** 运维提示（当前是硬删后的 VACUUM 建议）；由后端写入，前端原样展示 */
    private String remark;
}