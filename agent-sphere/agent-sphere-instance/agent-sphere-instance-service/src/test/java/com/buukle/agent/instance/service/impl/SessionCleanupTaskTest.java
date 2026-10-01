package com.buukle.agent.instance.service.impl;

import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.instance.dtvo.enums.RunEnum;
import com.buukle.agent.instance.dtvo.vo.SessionCleanupReportVO;
import com.buukle.agent.instance.repository.SessionCleanupMapper;
import com.buukle.agent.tasks.dtvo.enums.TaskEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.stubbing.OngoingStubbing;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SessionCleanupTask} 单测。
 *
 * <p>不连库不连 Redis（{@code @ExtendWith(MockitoExtension.class)} + mock），重点覆盖三件容易回归的事：
 * 活跃守卫是否真的挡住了会话、删除顺序是否满足外键、dry-run 是否真的不落刀。
 */
@ExtendWith(MockitoExtension.class)
class SessionCleanupTaskTest {

    private static final int BATCH_SIZE = 2;
    private static final int MAX_BATCHES = 5;
    private static final List<String> ACTIVE_RUN_STATUSES =
            List.of(RunEnum.STATUS_PENDING, RunEnum.STATUS_RUNNING);
    private static final List<String> ACTIVE_TASK_STATUSES =
            List.of(TaskEnum.STATUS_QUEUED, TaskEnum.STATUS_RUNNING);

    @Mock
    private SessionCleanupMapper sessionCleanupMapper;
    @Mock
    private SystemConfigSpi systemConfigSpi;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;
    @Mock
    private RAtomicLong atomicLong;

    private SessionCleanupTask task;

    @BeforeEach
    void setUp() {
        task = new SessionCleanupTask(sessionCleanupMapper, systemConfigSpi, redissonClient);
        ReflectionTestUtils.setField(task, "batchSize", BATCH_SIZE);
        ReflectionTestUtils.setField(task, "maxBatches", MAX_BATCHES);
        // 批间休眠设为 0：单测不该真的睡
        ReflectionTestUtils.setField(task, "batchSleepMs", 0);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
    }

    /** 干净的基础环境：配置正常、锁可拿、独立类别的 select 一律返回空。 */
    private void stubHappyPath() {
        lenient().when(lock.tryLock()).thenReturn(true);
        lenient().when(systemConfigSpi.get(eq(SystemConfigKeys.SESSION_CLEANUP_ENABLED), anyString()))
                .thenReturn("true");
        lenient().when(systemConfigSpi.get(eq(SystemConfigKeys.SESSION_CLEANUP_RETENTION_DAYS), anyString()))
                .thenReturn("7");
        lenient().when(systemConfigSpi.get(eq(SystemConfigKeys.SESSION_FILE_RETENTION_DAYS), anyString()))
                .thenReturn("7");
        lenient().when(sessionCleanupMapper.countActiveBlockedSessions(any(), any(), any())).thenReturn(0L);
        lenient().when(sessionCleanupMapper.selectOrphanTaskIds(any(), any(), anyInt())).thenReturn(List.of());
        lenient().when(sessionCleanupMapper.selectOrphanLlmInteractionIds(any(), anyInt())).thenReturn(List.of());
        lenient().when(sessionCleanupMapper.selectOrphanMemoryIds(any(), anyInt())).thenReturn(List.of());
        lenient().when(sessionCleanupMapper.selectExpiredFileIds(any(), any(), anyInt())).thenReturn(List.of());
        lenient().when(sessionCleanupMapper.selectExpiredCompletionsCallIds(any(), anyInt()))
                .thenReturn(List.of());
    }

    /** 让「过期会话」查询按批次依次返回，最后回空表示数据已取尽。 */
    @SafeVarargs
    private final void stubExpiredSessions(List<Long>... batches) {
        OngoingStubbing<List<Long>> stubbing = lenient()
                .when(sessionCleanupMapper.selectExpiredSessionIds(any(), any(), any(), anyInt()));
        for (List<Long> batch : batches) {
            stubbing = stubbing.thenReturn(batch);
        }
        stubbing.thenReturn(List.of());
    }

    @Test
    @DisplayName("锁被别的副本/请求持有时直接跳过，且一次 mapper 都不碰")
    void skipsWhenLockBusy() {
        when(lock.tryLock()).thenReturn(false);

        SessionCleanupReportVO report = task.runCleanup(false);

        assertTrue(report.isSkippedByLock());
        assertFalse(report.isEnabled());
        verifyNoInteractions(sessionCleanupMapper);
        verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("急停开关关掉时不删任何数据，也不释放锁失败")
    void skipsWhenDisabled() {
        when(lock.tryLock()).thenReturn(true);
        when(systemConfigSpi.get(eq(SystemConfigKeys.SESSION_CLEANUP_ENABLED), anyString()))
                .thenReturn("false");

        SessionCleanupReportVO report = task.runCleanup(false);

        assertFalse(report.isEnabled());
        assertTrue(report.getSkipReason().contains("session.cleanup-enabled"));
        verifyNoInteractions(sessionCleanupMapper);
        verify(lock).unlock();
    }

    @Test
    @DisplayName("dry-run 只统计，一条 DELETE 都不发")
    void dryRunNeverDeletes() {
        stubHappyPath();
        stubExpiredSessions(List.of(1L, 2L), List.of());
        when(sessionCleanupMapper.selectTaskIdsBySessionIds(any())).thenReturn(List.of(10L));
        when(sessionCleanupMapper.selectTaskIdsByRunSessionIds(any())).thenReturn(List.of(11L));
        when(sessionCleanupMapper.countCascadeBySessionIds(any(), any())).thenReturn(List.of(
                Map.of("table_name", "agent_timeline", "row_count", 5L),
                Map.of("table_name", "agent_run", "row_count", 2L)));

        SessionCleanupReportVO report = task.runCleanup(true);

        assertEquals(2, report.getSessionCount());
        assertEquals(5L, report.getTableStats().get("agent_timeline"));
        assertEquals(2L, report.getTableStats().get("agent_run"));
        verify(sessionCleanupMapper, never()).deleteSessionByIds(any());
        verify(sessionCleanupMapper, never()).deleteRunBySessionIds(any());
        verify(sessionCleanupMapper, never()).deleteTaskByIds(any());
        verify(sessionCleanupMapper, never()).deleteFilesByIds(any());
    }

    @Test
    @DisplayName("删除顺序：timeline → … → run → task_artifact → task → session（外键约束）")
    void deletesInForeignKeySafeOrder() {
        stubHappyPath();
        stubExpiredSessions(List.of(1L, 2L), List.of());
        when(sessionCleanupMapper.selectTaskIdsBySessionIds(any())).thenReturn(List.of(10L));
        when(sessionCleanupMapper.selectTaskIdsByRunSessionIds(any())).thenReturn(List.of(11L));
        when(sessionCleanupMapper.deleteTimelineBySessionIds(any())).thenReturn(1);
        when(sessionCleanupMapper.deleteRunBySessionIds(any())).thenReturn(1);
        when(sessionCleanupMapper.deleteTaskArtifactByTaskIds(any())).thenReturn(1);
        when(sessionCleanupMapper.deleteTaskByIds(any())).thenReturn(1);
        when(sessionCleanupMapper.deleteSessionByIds(any())).thenReturn(2);
        lenient().when(redissonClient.getAtomicLong(anyString())).thenReturn(atomicLong);

        task.runCleanup(false);

        InOrder order = inOrder(sessionCleanupMapper);
        order.verify(sessionCleanupMapper).deleteTimelineBySessionIds(List.of(1L, 2L));
        order.verify(sessionCleanupMapper).deleteLlmInteractionBySessionIds(any());
        order.verify(sessionCleanupMapper).deleteToolCallBySessionIds(any());
        order.verify(sessionCleanupMapper).deleteSubAgentRunBySessionIds(any());
        order.verify(sessionCleanupMapper).deletePendingClarificationBySessionIds(any());
        order.verify(sessionCleanupMapper).deleteSessionTodoBySessionIds(any());
        order.verify(sessionCleanupMapper).deleteCompactRecordBySessionIds(any());
        order.verify(sessionCleanupMapper).deleteUserInLoopBySessionIds(any());
        order.verify(sessionCleanupMapper).deleteMemoryBySessionIds(any());
        order.verify(sessionCleanupMapper).deleteDocumentBySessionIds(any());
        order.verify(sessionCleanupMapper).deleteRunBySessionIds(any());
        order.verify(sessionCleanupMapper).deleteTaskArtifactByTaskIds(List.of(10L, 11L));
        order.verify(sessionCleanupMapper).deleteTaskByIds(List.of(10L, 11L));
        order.verify(sessionCleanupMapper).deleteSessionByIds(any());
    }

    @Test
    @DisplayName("没有关联 task 时不发 task 相关 DELETE（IN () 在 PG 是语法错误）")
    void skipsTaskDeletesWhenNoTaskIds() {
        stubHappyPath();
        stubExpiredSessions(List.of(1L), List.of());
        when(sessionCleanupMapper.selectTaskIdsBySessionIds(any())).thenReturn(List.of());
        when(sessionCleanupMapper.selectTaskIdsByRunSessionIds(any())).thenReturn(List.of());
        when(sessionCleanupMapper.deleteSessionByIds(any())).thenReturn(1);
        lenient().when(redissonClient.getAtomicLong(anyString())).thenReturn(atomicLong);

        task.runCleanup(false);

        verify(sessionCleanupMapper, never()).deleteTaskArtifactByTaskIds(any());
        verify(sessionCleanupMapper, never()).deleteTaskByIds(any());
        verify(sessionCleanupMapper).deleteSessionByIds(List.of(1L));
    }

    @Test
    @DisplayName("活跃守卫：状态集合必须是 PENDING/RUNNING 与 QUEUED/RUNNING")
    void passesActiveStatusesFromEnums() {
        stubHappyPath();
        stubExpiredSessions(List.of(), List.of());

        task.runCleanup(false);

        verify(sessionCleanupMapper).selectExpiredSessionIds(any(LocalDateTime.class),
                eq(ACTIVE_RUN_STATUSES), eq(ACTIVE_TASK_STATUSES), eq(BATCH_SIZE));
        verify(sessionCleanupMapper).countActiveBlockedSessions(any(LocalDateTime.class),
                eq(ACTIVE_RUN_STATUSES), eq(ACTIVE_TASK_STATUSES));
    }

    @Test
    @DisplayName("被活跃守卫挡住的会话数要写进报告")
    void reportsActiveBlockedSessions() {
        when(lock.tryLock()).thenReturn(true);
        when(systemConfigSpi.get(eq(SystemConfigKeys.SESSION_CLEANUP_ENABLED), anyString())).thenReturn("true");
        when(systemConfigSpi.get(eq(SystemConfigKeys.SESSION_CLEANUP_RETENTION_DAYS), anyString()))
                .thenReturn("30");
        when(systemConfigSpi.get(eq(SystemConfigKeys.SESSION_FILE_RETENTION_DAYS), anyString()))
                .thenReturn("14");
        lenient().when(sessionCleanupMapper.countActiveBlockedSessions(any(), any(), any())).thenReturn(17L);
        lenient().when(sessionCleanupMapper.selectExpiredSessionIds(any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        lenient().when(sessionCleanupMapper.selectOrphanTaskIds(any(), any(), anyInt())).thenReturn(List.of());
        lenient().when(sessionCleanupMapper.selectOrphanLlmInteractionIds(any(), anyInt())).thenReturn(List.of());
        lenient().when(sessionCleanupMapper.selectOrphanMemoryIds(any(), anyInt())).thenReturn(List.of());
        lenient().when(sessionCleanupMapper.selectExpiredFileIds(any(), any(), anyInt())).thenReturn(List.of());
        lenient().when(sessionCleanupMapper.selectExpiredCompletionsCallIds(any(), anyInt()))
                .thenReturn(List.of());

        SessionCleanupReportVO report = task.runCleanup(true);

        assertEquals(17L, report.getSkippedActiveSessions());
        assertEquals(30, report.getRetentionDays());
        assertEquals(14, report.getFileRetentionDays());
    }

    @Test
    @DisplayName("配置写错（abc / 0 / 空）时回退默认 7，不能让清理停摆也不能删光")
    void fallsBackToDefaultRetentionDays() {
        when(lock.tryLock()).thenReturn(true);
        when(systemConfigSpi.get(eq(SystemConfigKeys.SESSION_CLEANUP_ENABLED), anyString())).thenReturn("1");
        when(systemConfigSpi.get(eq(SystemConfigKeys.SESSION_CLEANUP_RETENTION_DAYS), anyString()))
                .thenReturn("abc");
        when(systemConfigSpi.get(eq(SystemConfigKeys.SESSION_FILE_RETENTION_DAYS), anyString()))
                .thenReturn("0");
        lenient().when(sessionCleanupMapper.countActiveBlockedSessions(any(), any(), any())).thenReturn(0L);
        lenient().when(sessionCleanupMapper.selectExpiredSessionIds(any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        lenient().when(sessionCleanupMapper.selectOrphanTaskIds(any(), any(), anyInt())).thenReturn(List.of());
        lenient().when(sessionCleanupMapper.selectOrphanLlmInteractionIds(any(), anyInt())).thenReturn(List.of());
        lenient().when(sessionCleanupMapper.selectOrphanMemoryIds(any(), anyInt())).thenReturn(List.of());
        lenient().when(sessionCleanupMapper.selectExpiredFileIds(any(), any(), anyInt())).thenReturn(List.of());
        lenient().when(sessionCleanupMapper.selectExpiredCompletionsCallIds(any(), anyInt()))
                .thenReturn(List.of());

        SessionCleanupReportVO report = task.runCleanup(true);

        assertEquals(7, report.getRetentionDays());
        assertEquals(7, report.getFileRetentionDays());
        assertTrue(report.isEnabled());
    }

    @Test
    @DisplayName("批次上限：每轮都取满时按 max-batches 收口并标记 truncated")
    void stopsAtMaxBatches() {
        stubHappyPath();
        // 每一批都是满的（size == batchSize）→ 永远取不满，循环只能靠预算终止
        when(sessionCleanupMapper.selectExpiredSessionIds(any(), any(), any(), anyInt()))
                .thenReturn(List.of(1L, 2L));
        when(sessionCleanupMapper.selectTaskIdsBySessionIds(any())).thenReturn(List.of());
        when(sessionCleanupMapper.selectTaskIdsByRunSessionIds(any())).thenReturn(List.of());
        when(sessionCleanupMapper.deleteSessionByIds(any())).thenReturn(2);
        lenient().when(redissonClient.getAtomicLong(anyString())).thenReturn(atomicLong);

        SessionCleanupReportVO report = task.runCleanup(false);

        verify(sessionCleanupMapper, org.mockito.Mockito.times(MAX_BATCHES))
                .deleteSessionByIds(any());
        assertEquals(MAX_BATCHES * BATCH_SIZE, report.getSessionCount());
        assertTrue(report.isTruncated());
    }

    @Test
    @DisplayName("孤儿 task：先删 artifact 再删 task（artifact 是 task 的真外键）")
    void deletesOrphanArtifactsBeforeTasks() {
        stubHappyPath();
        lenient().when(sessionCleanupMapper.selectExpiredSessionIds(any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(sessionCleanupMapper.selectOrphanTaskIds(any(), any(), anyInt()))
                .thenReturn(List.of(20L, 21L))
                .thenReturn(List.of());
        when(sessionCleanupMapper.deleteTaskArtifactByTaskIds(any())).thenReturn(2);
        when(sessionCleanupMapper.deleteTaskByIds(any())).thenReturn(2);

        SessionCleanupReportVO report = task.runCleanup(false);

        InOrder order = inOrder(sessionCleanupMapper);
        order.verify(sessionCleanupMapper).selectOrphanTaskIds(any(), eq(ACTIVE_TASK_STATUSES), eq(BATCH_SIZE));
        order.verify(sessionCleanupMapper).deleteTaskArtifactByTaskIds(List.of(20L, 21L));
        order.verify(sessionCleanupMapper).deleteTaskByIds(List.of(20L, 21L));
        assertEquals(2L, report.getTableStats().get("agent_task"));
    }

    @Test
    @DisplayName("最后一批刚好取满但下次取空时，不应误报 truncated")
    void notTruncatedWhenFullyDrained() {
        stubHappyPath();
        // 第一批恰好等于 batchSize（看起来像「还有更多」），第二批取空 → 实际已清完
        stubExpiredSessions(List.of(1L, 2L), List.of());
        when(sessionCleanupMapper.selectTaskIdsBySessionIds(any())).thenReturn(List.of());
        when(sessionCleanupMapper.selectTaskIdsByRunSessionIds(any())).thenReturn(List.of());
        when(sessionCleanupMapper.deleteSessionByIds(any())).thenReturn(2);
        lenient().when(redissonClient.getAtomicLong(anyString())).thenReturn(atomicLong);

        SessionCleanupReportVO report = task.runCleanup(false);

        assertFalse(report.isTruncated());
    }

    @Test
    @DisplayName("删除过程中抛异常时锁仍然释放")
    void releasesLockOnFailure() {
        stubHappyPath();
        stubExpiredSessions(List.of(1L, 2L), List.of());
        when(sessionCleanupMapper.selectTaskIdsBySessionIds(any())).thenReturn(List.of());
        when(sessionCleanupMapper.selectTaskIdsByRunSessionIds(any())).thenReturn(List.of());
        when(sessionCleanupMapper.deleteTimelineBySessionIds(any()))
                .thenThrow(new RuntimeException("db down"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> task.runCleanup(false));

        verify(lock).unlock();
    }
}
