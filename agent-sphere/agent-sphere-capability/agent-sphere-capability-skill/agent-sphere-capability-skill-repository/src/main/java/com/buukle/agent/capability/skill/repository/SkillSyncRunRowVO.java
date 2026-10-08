package com.buukle.agent.capability.skill.repository;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * {@code capability_skill_sync_run} 的数据库行形态（jsonb 取文本）。
 *
 * <p>与 API 层的 {@code SkillSyncRunVO} 只差 {@code detail} 的类型：MyBatis 对自动映射
 * 不会凭空把 JSON 文本变成 List，由调用方解析后再装进 VO。
 */
@Data
public class SkillSyncRunRowVO implements Serializable {

    private Long id;
    private String triggerType;
    private String status;
    private Integer scannedCount;
    private Integer handled;
    private Integer updated;
    private Integer skipped;
    private Integer batchSize;
    /** 跳过的明细 JSON 文本 */
    private String detail;
    private String skipReason;
    private String errorMessage;
    private Long elapsedMs;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    /** 疑似中断：未落终态且开始时间已超过阈值 */
    private Boolean stale;
    private String createdBy;
}
