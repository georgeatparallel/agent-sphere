package com.buukle.agent.instance.domain.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 会话清理执行记录的<strong>数据库行形态</strong>，只供 {@code SessionCleanupRunMapper} 使用。
 *
 * <p>与 API 层 {@code SessionCleanupRunVO}（instance-dtvo）的唯一差别是 {@code tableStats} 为字符串：列类型是 jsonb，
 * MyBatis 对自动映射无法凭空把 JSON 文本变成 {@code Map}（{@code JsonbTypeHandler} 在
 * infrastructure，而 instance 不能反向依赖它）。所以这里取 {@code CAST(table_stats AS text)}，
 * 由调用方用 JsonUtils 转成 Map 再装进 VO。
 */
@Data
public class SessionCleanupRunRowVO implements Serializable {

    private Long id;
    private String triggerType;
    private boolean dryRun;
    private String status;
    private Integer retentionDays;
    private Integer fileRetentionDays;
    private LocalDateTime cutoff;
    private LocalDateTime fileCutoff;
    private Integer sessionCount;
    /** 进度分母：执行前 count 出的过期会话总数（V78 新增列） */
    private Integer totalSessions;
    private Long totalRows;
    private Long skippedActiveSessions;
    private Integer batches;
    private Boolean truncated;
    private Long elapsedMs;
    /** jsonb 文本形态 */
    private String tableStats;
    private String skipReason;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    /** 疑似中断：未落终态且开始时间已超过阈值 */
    private Boolean stale;
    private String createdBy;
    /** 后端写给运维的提示（当前用于承载 VACUUM 建议；该表由机器写入，无人工备注场景） */
    private String remark;
}