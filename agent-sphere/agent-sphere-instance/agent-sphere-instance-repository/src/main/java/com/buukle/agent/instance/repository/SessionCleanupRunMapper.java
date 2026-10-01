package com.buukle.agent.instance.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.instance.domain.vo.SessionCleanupRunRowVO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 会话清理的执行记录读写（{@code agent_session_cleanup_run}）。
 *
 * <p>刻意用裸 SQL 而不是 Entity + BaseMapper：
 * <ul>
 *   <li>{@code table_stats} 是 jsonb，走 MyBatis-Plus 要么塞 {@code String} 被 PG 拒绝
 *       （列类型不匹配），要么依赖 infrastructure 的 {@code JsonbTypeHandler} —— 而
 *       infrastructure 已依赖 instance-service，反向引用会成环；</li>
 *   <li>本表只在清理任务里用，字段少，不需要 MP 的 CRUD 能力。</li>
 * </ul>
 * 写入用 {@code CAST(#{x} AS jsonb)}，读取用 {@code CAST(table_stats AS text)}。
 *
 * <p>记录本身也参与清理（{@link #deleteOlderThan}），由 {@code session.cleanup-log-retention-days} 驱动。
 */
@Mapper
public interface SessionCleanupRunMapper {

    /**
     * 开一条 RUNNING 记录。
     *
     * <p>用 {@code INSERT … RETURNING id} 而不是 generated keys：单参数 {@code @Insert} 拿不到自增主键。
     *
     * <p><b>必须在抢清理锁之前调用</b>——「锁被占用」「急停开关关闭」这两种什么都没做的情况
     * 也要留痕，否则事后无法解释「为什么昨晚磁盘没降下来」。
     */
    @Select("""
            INSERT INTO agent_session_cleanup_run (trigger_type, dry_run, status, created_by, updated_by)
            VALUES (#{triggerType}, #{dryRun}, #{statusRunning}, #{createdBy}, #{createdBy})
            RETURNING id
            """)
    Long insertRunning(@Param("triggerType") String triggerType,
                       @Param("dryRun") boolean dryRun,
                       @Param("statusRunning") String statusRunning,
                       @Param("createdBy") String createdBy);

    /** 落 SUCCESS：回填口径、命中量、耗时与各表明细。 */
    @Update("""
            UPDATE agent_session_cleanup_run
            SET status = #{statusSuccess}, retention_days = #{retentionDays}, file_retention_days = #{fileRetentionDays},
                cutoff = #{cutoff}, file_cutoff = #{fileCutoff}, session_count = #{sessionCount},
                total_sessions = #{totalSessions},
                total_rows = #{totalRows}, skipped_active_sessions = #{skippedActiveSessions},
                batches = #{batches}, truncated = #{truncated}, elapsed_ms = #{elapsedMs},
                table_stats = CAST(#{tableStatsJson} AS jsonb),
                remark = #{remark},
                finished_at = NOW(), updated_by = #{updatedBy}, updated_at = NOW()
            WHERE id = #{id}
            """)
    int markSuccess(@Param("id") Long id,
                    @Param("statusSuccess") String statusSuccess,
                    @Param("retentionDays") int retentionDays,
                    @Param("fileRetentionDays") int fileRetentionDays,
                    @Param("cutoff") LocalDateTime cutoff,
                    @Param("fileCutoff") LocalDateTime fileCutoff,
                    @Param("sessionCount") int sessionCount,
                    @Param("totalSessions") int totalSessions,
                    @Param("totalRows") long totalRows,
                    @Param("skippedActiveSessions") long skippedActiveSessions,
                    @Param("batches") int batches,
                    @Param("truncated") boolean truncated,
                    @Param("elapsedMs") long elapsedMs,
                    @Param("tableStatsJson") String tableStatsJson,
                    @Param("remark") String remark,
                    @Param("updatedBy") String updatedBy);

    /**
     * 渐进式进度：每处理完一批就更新一次，让前端进度条能动起来。
     *
     * <p>不写 {@code finished_at}，也不改 {@code status} —— 记录仍在 RUNNING。
     * 单轮最多 50 次（cleanup-max-batches），开销可忽略。
     */
    @Update("""
            UPDATE agent_session_cleanup_run
            SET total_sessions = #{totalSessions},
                session_count = #{sessionCount},
                total_rows = #{totalRows},
                batches = #{batches},
                table_stats = CAST(#{tableStatsJson} AS jsonb),
                elapsed_ms = #{elapsedMs},
                updated_by = #{updatedBy}, updated_at = NOW()
            WHERE id = #{id}
            """)
    int markProgress(@Param("id") Long id,
                     @Param("totalSessions") int totalSessions,
                     @Param("sessionCount") int sessionCount,
                     @Param("totalRows") long totalRows,
                     @Param("batches") int batches,
                     @Param("elapsedMs") long elapsedMs,
                     @Param("tableStatsJson") String tableStatsJson,
                     @Param("updatedBy") String updatedBy);

    /** 轮询用：取单条记录（含进度字段）。 */
    @Select("""
            <script>
            SELECT id, trigger_type, dry_run, status, retention_days, file_retention_days,
                   cutoff, file_cutoff, session_count, total_sessions, total_rows, skipped_active_sessions,
                   batches, truncated, elapsed_ms,
                   CAST(table_stats AS text) AS table_stats,
                   skip_reason, error_message, started_at, finished_at, created_by, remark,
                   (finished_at IS NULL AND started_at &lt; #{staleBefore}) AS stale
            FROM agent_session_cleanup_run
            WHERE id = #{id}
            </script>
            """)
    SessionCleanupRunRowVO getRun(@Param("id") Long id,
                                  @Param("staleBefore") LocalDateTime staleBefore);

    /** 落 SKIPPED：锁被占用或急停开关关闭，什么都没删。 */
    @Update("""
            UPDATE agent_session_cleanup_run
            SET status = #{statusSkipped}, skip_reason = #{skipReason},
                elapsed_ms = #{elapsedMs}, finished_at = NOW(),
                updated_by = #{updatedBy}, updated_at = NOW()
            WHERE id = #{id}
            """)
    int markSkipped(@Param("id") Long id,
                    @Param("statusSkipped") String statusSkipped,
                    @Param("skipReason") String skipReason,
                    @Param("elapsedMs") long elapsedMs,
                    @Param("updatedBy") String updatedBy);

    /** 落 FAILED：记录异常摘要；清理异常照常向上抛，不在这里吞掉。 */
    @Update("""
            UPDATE agent_session_cleanup_run
            SET status = #{statusFailed}, error_message = #{errorMessage},
                elapsed_ms = #{elapsedMs}, finished_at = NOW(),
                updated_by = #{updatedBy}, updated_at = NOW()
            WHERE id = #{id}
            """)
    int markFailed(@Param("id") Long id,
                   @Param("statusFailed") String statusFailed,
                   @Param("errorMessage") String errorMessage,
                   @Param("elapsedMs") long elapsedMs,
                   @Param("updatedBy") String updatedBy);

    /** 执行记录分页（默认按开始时间倒序）。{@code staleBefore} 决定何时算「疑似中断」。 */
    @Select("""
            <script>
            SELECT id, trigger_type, dry_run, status, retention_days, file_retention_days,
                   cutoff, file_cutoff, session_count, total_sessions, total_rows, skipped_active_sessions,
                   batches, truncated, elapsed_ms,
                   CAST(table_stats AS text) AS table_stats,
                   skip_reason, error_message, started_at, finished_at, created_by, remark,
                   (finished_at IS NULL AND started_at &lt; #{staleBefore}) AS stale
            FROM agent_session_cleanup_run
            <where>
                <if test="triggerType != null">AND trigger_type = #{triggerType}</if>
                <if test="status != null">AND status = #{status}</if>
                <if test="dryRun != null">AND dry_run = #{dryRun}</if>
            </where>
            ORDER BY started_at DESC, id DESC
            </script>
            """)
    IPage<SessionCleanupRunRowVO> pageRuns(Page<SessionCleanupRunRowVO> page,
                                           @Param("triggerType") String triggerType,
                                           @Param("status") String status,
                                           @Param("dryRun") Boolean dryRun,
                                           @Param("staleBefore") LocalDateTime staleBefore);

    /** 记录表自身的保留策略：删掉过期记录，避免清理机制自己变成新垃圾。 */
    @Delete("""
            DELETE FROM agent_session_cleanup_run
            WHERE created_at < #{cutoff} AND id <> #{keepId}
            """)
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("keepId") Long keepId);

    /** dry-run 用：只数不删。 */
    @Select("""
            SELECT count(*) FROM agent_session_cleanup_run
            WHERE created_at < #{cutoff} AND id <> #{keepId}
            """)
    long countOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("keepId") Long keepId);
}