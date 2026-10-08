package com.buukle.agent.capability.skill.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/** Skill 自动同步的一轮执行记录（{@code capability_skill_sync_run} 一行）。 */
@Data
public class SkillSyncRunVO implements Serializable {

    private Long id;
    /** SCHEDULED / MANUAL */
    private String triggerType;
    /** RUNNING / SUCCESS / SKIPPED / FAILED */
    private String status;
    /** 本轮扫描到的「开启了自动更新的副本」总数 */
    private Integer scannedCount;
    /** 实际处理数 */
    private Integer handled;
    private Integer updated;
    private Integer skipped;
    private Integer batchSize;
    /** 跳过的明细：每个副本一条，含原因 */
    private List<SkippedCopy> detail;
    private String skipReason;
    private String errorMessage;
    private Long elapsedMs;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    /** 疑似中断：进程崩了导致记录永远停在 RUNNING */
    private Boolean stale;
    private String createdBy;

    /** 单个副本被跳过的原因。 */
    @Data
    public static class SkippedCopy implements Serializable {
        private Long copyId;
        private Long originId;
        /** SkillSyncOutcome 里的非 UPDATED 取值 */
        private String reason;
        private Integer originVersion;
        private Integer sourceVersion;
    }
}
