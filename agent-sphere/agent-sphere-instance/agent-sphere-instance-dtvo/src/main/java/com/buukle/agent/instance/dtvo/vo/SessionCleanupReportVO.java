package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 历史会话清理报告（定时任务与手动接口共用同一结构）。
 *
 * <p>{@code tableStats} 的 key 是表名、value 是受影响行数：dry-run 时是「将会删除」的行数，
 * 实际执行时是「已删除」的行数。顺序按删除顺序，便于人工核对。
 */
@Data
public class SessionCleanupReportVO implements Serializable {

    /** 本次是否为预演（true=只统计不删除） */
    private boolean dryRun;

    /** 清理开关（session.cleanup-enabled）；false 表示本次直接跳过 */
    private boolean enabled;

    /** 另一副本或另一次手动执行正持锁时为 true，此时未做任何事 */
    private boolean skippedByLock;

    private String skipReason;

    /** 实际生效的保留天数（配置非法时已回退默认值） */
    private int retentionDays;

    private int fileRetentionDays;

    /** 过期判定线：created_at 与 updated_at 均早于该时间即过期 */
    private LocalDateTime cutoff;

    private LocalDateTime fileCutoff;

    /** 删除的会话数 */
    private int sessionCount;

    /** 因仍在跑（PENDING/RUNNING 的 run 或 task）而被活跃守卫挡住的会话数 */
    private long skippedActiveSessions;

    /** 各表受影响行数 */
    private Map<String, Long> tableStats = new LinkedHashMap<>();

    /** 已处理批次数（受 max-batches 限制，达到上限会提前结束） */
    private int batches;

    /** 是否因达到单轮批次数上限而提前结束（false=已清完所有过期数据） */
    private boolean truncated;

    private long elapsedMs;

    /** 硬删后空间只回到库内可复用，数据文件不会自动变小；此字段是给运维的处置建议 */
    private String vacuumHint;
}
