package com.buukle.agent.instance.service.impl;

import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.common.constant.FileStoreBizKeys;
import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import com.buukle.agent.instance.dtvo.enums.RunEnum;
import com.buukle.agent.instance.dtvo.vo.SessionCleanupReportVO;
import com.buukle.agent.instance.repository.SessionCleanupMapper;
import com.buukle.agent.tasks.dtvo.enums.TaskEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 历史会话数据周期清理：释放磁盘。
 *
 * <p><b>为什么必须硬删：</b>session 子树里 {@code agent_llm_interaction_record} 的
 * request/response/reasoning 与 {@code agent_tool_call_record} 的参数、产物都是全量 TEXT，
 * {@code @TableLogic} 只把 DELETE 改写成 {@code UPDATE delete_flag=1}，行数据原样留在堆表里，
 * <b>一行磁盘都不会释放</b>。这是对「只做逻辑删除」约定的显式豁免，仅限本任务。
 *
 * <p><b>过期判定</b>：{@code agent_session.created_at < cutoff AND updated_at < cutoff}，
 * 双条件是为了不误删「创建很久、今天仍在用」的会话；再叠加两个 NOT EXISTS 活跃守卫，
 * 保证 PENDING/RUNNING 的 run 与 QUEUED/RUNNING 的 task 所在会话一定不会被碰。
 *
 * <p><b>刻意不把 AWAITING_USER 算作活跃</b>：等用户回复的澄清可能永远不回来，纳入守卫会让
 * 废弃会话永久挡在清理之外，正好抵消清理的意义。
 *
 * <p><b>为什么落在 instance 模块而不是 infrastructure：</b>infrastructure 已依赖
 * instance-service，反向依赖会成环；session 本身也是 instance 域的实体。
 * {@code AuditLogCleanupTask} 留在 infrastructure —— 审计日志属基础设施域，无需搬迁。
 *
 * <p>调度参数（cron/批大小）在 yml；保留天数与急停开关在系统配置表 {@code config_group='session'}，
 * 改完不必发版。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionCleanupTask {

    /** 多副本互斥锁：后端 replicas=2，不加锁两个副本会同时删。 */
    private static final String CLEANUP_LOCK_KEY = "scheduler:session-cleanup";

    private static final int DEFAULT_RETENTION_DAYS = 7;
    private static final int DEFAULT_FILE_RETENTION_DAYS = 7;

    /** 活跃 run：正在跑，删了等于毁掉进行中的会话。 */
    private static final List<String> ACTIVE_RUN_STATUSES =
            List.of(RunEnum.STATUS_PENDING, RunEnum.STATUS_RUNNING);

    /** 活跃 task：Bole 开放层长任务只更新 task/run 行、不更新 session 行，必须单独守卫。 */
    private static final List<String> ACTIVE_TASK_STATUSES =
            List.of(TaskEnum.STATUS_QUEUED, TaskEnum.STATUS_RUNNING);

    /** 会话产物落库的桶（截图/附件），按时间独立清理。 */
    private static final List<String> CLEANUP_BIZ_KEYS =
            List.of(FileStoreBizKeys.BROWSER_SCREENSHOT, FileStoreBizKeys.CHAT_ATTACHMENT);

    /**
     * 硬删只把空间还给 PG 内部复用，数据文件不会自动变小；要归还给操作系统必须低峰期手工
     * VACUUM FULL（ACCESS EXCLUSIVE 锁，期间阻塞写入）。故只提示、不自动执行。
     */
    private static final String VACUUM_HINT =
            "空间已回收至库内可复用，数据文件不会自动变小。如需归还磁盘请低峰期手工执行："
                    + "VACUUM (FULL, ANALYZE) agent_llm_interaction_record, agent_tool_call_record, agent_file_store; "
                    + "（需 ACCESS EXCLUSIVE 锁，期间阻塞写入）";

    private static final String SKIP_LOCK_REASON = "另一副本或另一次清理正在执行，本次跳过";
    private static final String SKIP_DISABLED_REASON = "清理开关 session.cleanup-enabled 为 false，本次跳过";

    private final SessionCleanupMapper sessionCleanupMapper;
    private final SystemConfigSpi systemConfigSpi;
    private final RedissonClient redissonClient;

    @Value("${buukle.agent.session.cleanup-batch-size:200}")
    private int batchSize;

    @Value("${buukle.agent.session.cleanup-max-batches:50}")
    private int maxBatches;

    @Value("${buukle.agent.session.cleanup-batch-sleep-ms:200}")
    private int batchSleepMs;

    /** 定时入口：低峰期执行（默认 04:30）。 */
    @Scheduled(cron = "${buukle.agent.session.cleanup-cron:0 30 4 * * ?}")
    public void scheduledCleanup() {
        runCleanup(false);
    }

    /**
     * 执行一轮清理。
     *
     * @param dryRun true 时只统计「将会删除多少行」，不落刀
     * @return 报告（定时任务忽略返回值，手动接口直接回给前端）
     */
    public SessionCleanupReportVO runCleanup(boolean dryRun) {
        long startedAt = System.currentTimeMillis();
        SessionCleanupReportVO report = new SessionCleanupReportVO();
        report.setDryRun(dryRun);

        RLock lock = redissonClient.getLock(CLEANUP_LOCK_KEY);
        if (!lock.tryLock()) {
            report.setSkippedByLock(true);
            report.setSkipReason(SKIP_LOCK_REASON);
            return report;
        }
        try {
            if (!isCleanupEnabled()) {
                report.setEnabled(false);
                report.setSkipReason(SKIP_DISABLED_REASON);
                return report;
            }
            report.setEnabled(true);

            int retentionDays = resolveRetentionDays();
            int fileRetentionDays = resolveFileRetentionDays();
            BatchBudget budget = new BatchBudget(Math.max(maxBatches, 1));
            int sizeLimit = Math.max(batchSize, 1);

            LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
            LocalDateTime fileCutoff = LocalDateTime.now().minusDays(fileRetentionDays);
            report.setRetentionDays(retentionDays);
            report.setFileRetentionDays(fileRetentionDays);
            report.setCutoff(cutoff);
            report.setFileCutoff(fileCutoff);
            report.setSkippedActiveSessions(sessionCleanupMapper.countActiveBlockedSessions(
                    cutoff, ACTIVE_RUN_STATUSES, ACTIVE_TASK_STATUSES));

            cleanupExpiredSessions(cutoff, dryRun, sizeLimit, budget, report);
            cleanupIndependent(cutoff, fileCutoff, dryRun, sizeLimit, budget, report);
            report.setTruncated(budget.truncated);
            report.setElapsedMs(System.currentTimeMillis() - startedAt);
            report.setVacuumHint(dryRun ? null : VACUUM_HINT);
            logSummary(report);
            return report;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 过期会话级联删除。
     *
     * <p>策略是「先用 {@code agent_session} 一张表选出目标 id 集合，再按 session_id 级联删子表」，
     * 而不是给每张子表各跑一次时间过滤：子表的 session_id 索引齐全（V74 补齐了驱动表与
     * agent_task），而逐表过滤会漏掉「会话很老、子行却是最近写的」这类数据。
     */
    private void cleanupExpiredSessions(LocalDateTime cutoff, boolean dryRun, int sizeLimit,
                                        BatchBudget budget, SessionCleanupReportVO report) {
        while (budget.hasNext()) {
            List<Long> sessionIds = sessionCleanupMapper.selectExpiredSessionIds(
                    cutoff, ACTIVE_RUN_STATUSES, ACTIVE_TASK_STATUSES, sizeLimit);
            if (sessionIds.isEmpty()) {
                // 取空 = 这一类已彻底清完，不存在「还有数据」
                return;
            }
            budget.consume();
            report.setSessionCount(report.getSessionCount() + sessionIds.size());

            List<Long> taskIds = collectTaskIds(sessionIds);
            if (dryRun) {
                accumulateCounts(sessionIds, taskIds, report);
            } else {
                deleteCascade(sessionIds, taskIds, report);
                deleteTimelineSeqKeys(sessionIds);
                sleepBetweenBatches();
            }
        }
        // 因批次预算耗尽而退出，后面很可能还有数据
        budget.markTruncated();
    }

    /**
     * 不挂在 session 上的独立/孤儿数据：孤儿 task、session_id 为空的孤儿行、截图附件、completions 日志。
     * 与级联路径共用同一个批次数预算（max-batches 是「单轮上限」而非「每个类别上限」）。
     */
    private void cleanupIndependent(LocalDateTime cutoff, LocalDateTime fileCutoff, boolean dryRun,
                                    int sizeLimit, BatchBudget budget, SessionCleanupReportVO report) {
        // 孤儿 task：artifact 是 task 的真外键，必须先删 artifact 再删 task
        drain(limit -> sessionCleanupMapper.selectOrphanTaskIds(cutoff, ACTIVE_TASK_STATUSES, limit),
                ids -> {
                    if (dryRun) {
                        addStat(report, "agent_task_artifact",
                                sessionCleanupMapper.countArtifactsByTaskIds(ids));
                        addStat(report, "agent_task", ids.size());
                    } else {
                        addStat(report, "agent_task_artifact",
                                sessionCleanupMapper.deleteTaskArtifactByTaskIds(ids));
                        addStat(report, "agent_task", sessionCleanupMapper.deleteTaskByIds(ids));
                    }
                }, dryRun, sizeLimit, budget);

        drain(limit -> sessionCleanupMapper.selectOrphanLlmInteractionIds(cutoff, limit), ids -> {
            if (dryRun) {
                addStat(report, "agent_llm_interaction_record", ids.size());
            } else {
                addStat(report, "agent_llm_interaction_record",
                        sessionCleanupMapper.deleteLlmInteractionsByIds(ids));
            }
        }, dryRun, sizeLimit, budget);

        drain(limit -> sessionCleanupMapper.selectOrphanMemoryIds(cutoff, limit), ids -> {
            if (dryRun) {
                addStat(report, "agent_memory", ids.size());
            } else {
                addStat(report, "agent_memory", sessionCleanupMapper.deleteMemoriesByIds(ids));
            }
        }, dryRun, sizeLimit, budget);

        drain(limit -> sessionCleanupMapper.selectExpiredFileIds(fileCutoff, CLEANUP_BIZ_KEYS, limit), ids -> {
            if (dryRun) {
                addStat(report, "agent_file_store", ids.size());
            } else {
                addStat(report, "agent_file_store", sessionCleanupMapper.deleteFilesByIds(ids));
            }
        }, dryRun, sizeLimit, budget);

        drain(limit -> sessionCleanupMapper.selectExpiredCompletionsCallIds(cutoff, limit), ids -> {
            if (dryRun) {
                addStat(report, "agent_completions_call", ids.size());
            } else {
                addStat(report, "agent_completions_call", sessionCleanupMapper.deleteCompletionsCallsByIds(ids));
            }
        }, dryRun, sizeLimit, budget);
    }

    /** 反复取一批 id 处理掉，直到取空或批次数预算耗尽。 */
    private void drain(IdBatchLoader loader, Consumer<List<Long>> handler, boolean dryRun,
                       int sizeLimit, BatchBudget budget) {
        while (budget.hasNext()) {
            List<Long> ids = loader.load(sizeLimit);
            if (ids.isEmpty()) {
                return;
            }
            budget.consume();
            handler.accept(ids);
            if (!dryRun) {
                sleepBetweenBatches();
            }
        }
        budget.markTruncated();
    }

    @FunctionalInterface
    private interface IdBatchLoader {
        List<Long> load(int limit);
    }

    /** 两路合并：session_id 命中的 task ∪ 本批 run 的 task_id 指向的 task。 */
    private List<Long> collectTaskIds(List<Long> sessionIds) {
        Set<Long> taskIds = new LinkedHashSet<>(sessionCleanupMapper.selectTaskIdsBySessionIds(sessionIds));
        taskIds.addAll(sessionCleanupMapper.selectTaskIdsByRunSessionIds(sessionIds));
        return new ArrayList<>(taskIds);
    }

    /** dry-run：一条 UNION ALL 拿到这一批 14 张表的行数。 */
    private void accumulateCounts(List<Long> sessionIds, List<Long> taskIds, SessionCleanupReportVO report) {
        for (Map<String, Object> row : sessionCleanupMapper.countCascadeBySessionIds(sessionIds, taskIds)) {
            Object name = row.get("table_name");
            Object count = row.get("row_count");
            if (name != null && count instanceof Number number) {
                addStat(report, String.valueOf(name), number.longValue());
            }
        }
    }

    /**
     * 级联删除。<b>顺序即外键约束，不可调换</b>：
     * {@code agent_run.session_id → agent_session} 与 {@code agent_task_artifact.task_id → agent_task}
     * 是全库仅有的两条真外键，且都没有 ON DELETE CASCADE，顺序错了会直接
     * violates foreign key constraint。
     */
    private void deleteCascade(List<Long> sessionIds, List<Long> taskIds, SessionCleanupReportVO report) {
        addStat(report, "agent_timeline", sessionCleanupMapper.deleteTimelineBySessionIds(sessionIds));
        addStat(report, "agent_llm_interaction_record",
                sessionCleanupMapper.deleteLlmInteractionBySessionIds(sessionIds));
        addStat(report, "agent_tool_call_record", sessionCleanupMapper.deleteToolCallBySessionIds(sessionIds));
        addStat(report, "agent_sub_agent_run", sessionCleanupMapper.deleteSubAgentRunBySessionIds(sessionIds));
        addStat(report, "agent_pending_clarification",
                sessionCleanupMapper.deletePendingClarificationBySessionIds(sessionIds));
        addStat(report, "agent_session_todo", sessionCleanupMapper.deleteSessionTodoBySessionIds(sessionIds));
        addStat(report, "agent_compact_record", sessionCleanupMapper.deleteCompactRecordBySessionIds(sessionIds));
        addStat(report, "agent_user_in_loop_record", sessionCleanupMapper.deleteUserInLoopBySessionIds(sessionIds));
        addStat(report, "agent_memory", sessionCleanupMapper.deleteMemoryBySessionIds(sessionIds));
        addStat(report, "agent_document", sessionCleanupMapper.deleteDocumentBySessionIds(sessionIds));
        addStat(report, "agent_run", sessionCleanupMapper.deleteRunBySessionIds(sessionIds));
        if (!taskIds.isEmpty()) {
            addStat(report, "agent_task_artifact", sessionCleanupMapper.deleteTaskArtifactByTaskIds(taskIds));
            addStat(report, "agent_task", sessionCleanupMapper.deleteTaskByIds(taskIds));
        }
        addStat(report, "agent_session", sessionCleanupMapper.deleteSessionByIds(sessionIds));
    }

    /** timeline 序号键没有 TTL，删会话必须一并删，否则永久残留。 */
    private void deleteTimelineSeqKeys(List<Long> sessionIds) {
        for (Long sessionId : sessionIds) {
            redissonClient.getAtomicLong(DistributedRuntimeConstants.timelineSeqKey(sessionId)).delete();
        }
    }

    private void addStat(SessionCleanupReportVO report, String table, long rows) {
        report.getTableStats().merge(table, rows, Long::sum);
    }

    /** 批间让位给 autovacuum：否则一轮几百 MB 的删除产生的死元组会一直堆着。 */
    private void sleepBetweenBatches() {
        if (batchSleepMs <= 0) {
            return;
        }
        try {
            Thread.sleep(batchSleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("会话清理被中断", e);
        }
    }

    private boolean isCleanupEnabled() {
        return parseBoolean(systemConfigSpi.get(SystemConfigKeys.SESSION_CLEANUP_ENABLED, "true"), true);
    }

    private int resolveRetentionDays() {
        return parsePositiveInt(systemConfigSpi.get(SystemConfigKeys.SESSION_CLEANUP_RETENTION_DAYS,
                String.valueOf(DEFAULT_RETENTION_DAYS)), DEFAULT_RETENTION_DAYS,
                SystemConfigKeys.SESSION_CLEANUP_RETENTION_DAYS);
    }

    private int resolveFileRetentionDays() {
        return parsePositiveInt(systemConfigSpi.get(SystemConfigKeys.SESSION_FILE_RETENTION_DAYS,
                String.valueOf(DEFAULT_FILE_RETENTION_DAYS)), DEFAULT_FILE_RETENTION_DAYS,
                SystemConfigKeys.SESSION_FILE_RETENTION_DAYS);
    }

    /** 配置非法（空串/非数字/非正数）时回退默认值并告警，不因配置写错而让清理停摆或删光数据。 */
    private int parsePositiveInt(String raw, int fallback, String key) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value > 0) {
                return value;
            }
            log.warn("Session cleanup: {}={} 不是正数，回退默认值 {}", key, raw, fallback);
        } catch (NumberFormatException e) {
            log.warn("Session cleanup: {}={} 不是合法整数，回退默认值 {}", key, raw, fallback);
        }
        return fallback;
    }

    private boolean parseBoolean(String raw, boolean fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim();
        if ("true".equalsIgnoreCase(value) || "1".equals(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "0".equals(value)) {
            return false;
        }
        log.warn("Session cleanup: 开关值 {} 不是合法布尔值，回退默认值 {}", value, fallback);
        return fallback;
    }

    /** 只在真有东西被删时才打 INFO（对齐 AuditLogCleanupTask，避免每轮刷屏）；dry-run 是人工请求，必打。 */
    private void logSummary(SessionCleanupReportVO report) {
        long total = report.getTableStats().values().stream().mapToLong(Long::longValue).sum();
        if (!report.isDryRun() && total == 0) {
            return;
        }
        log.info("Session cleanup {}: sessions={} activeBlocked={} batches={} truncated={} totalRows={} stats={} elapsed={}ms",
                report.isDryRun() ? "dry-run" : "done",
                report.getSessionCount(), report.getSkippedActiveSessions(), report.getBatches(),
                report.isTruncated(), total, report.getTableStats(), report.getElapsedMs());
        if (!report.isDryRun() && total > 0) {
            log.info("Session cleanup hint: {}", VACUUM_HINT);
        }
    }

    /**
     * 单轮批次数预算：级联路径与各独立清理类别<b>共用</b>同一预算，
     * 保证一次运行最多处理 max-batches 批，不会因为类别多而失控跑很久。
     *
     * <p>{@code truncated} 的语义是「可能还有数据没处理」：只有因预算耗尽而退出才会置位；
     * 取空返回说明该类别已清完，不置位 —— 否则「最后一批刚好取满」会被误报成没清干净。
     */
    private static final class BatchBudget {

        private final int limit;
        private int used;
        private boolean truncated;

        private BatchBudget(int limit) {
            this.limit = limit;
        }

        private boolean hasNext() {
            return used < limit;
        }

        private void consume() {
            used++;
        }

        private void markTruncated() {
            truncated = true;
        }
    }
}
