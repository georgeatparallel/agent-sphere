package com.buukle.agent.capability.skill.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * Skill 自动同步的执行记录读写（{@code capability_skill_sync_run}）。
 *
 * <p>裸 SQL 而非 Entity：{@code detail} 是 jsonb，MP 实体要么塞 String 被 PG 拒绝，
 * 要么依赖 infrastructure 的 JsonbTypeHandler（而 infrastructure 已依赖 instance-service，
 * 业务模块不能反向引它）。写入用 {@code CAST(#{x} AS jsonb)}，读取用 {@code CAST(detail AS text)}。
 *
 * <p>这张表同时是「任务到底跑没跑」的唯一证据：sweeper 的 logger 被 logback 的
 * {@code com.buukle.agent.capability=WARN} 规则压住，log.info 永不落盘。
 */
@Mapper
public interface SkillSyncRunMapper {

    /** 插一条 RUNNING。id 由 {@code INSERT … RETURNING id} 取回。 */
    @Select("""
            INSERT INTO capability_skill_sync_run (trigger_type, status, created_by, updated_by)
            VALUES (#{triggerType}, #{statusRunning}, #{createdBy}, #{createdBy})
            RETURNING id
            """)
    Long insertRunning(@Param("triggerType") String triggerType,
                       @Param("statusRunning") String statusRunning,
                       @Param("createdBy") String createdBy);

    /** 落 SUCCESS，回填本轮统计与跳过明细。 */
    @Update("""
            UPDATE capability_skill_sync_run
            SET status = #{statusSuccess}, scanned_count = #{scannedCount}, handled = #{handled},
                updated = #{updated}, skipped = #{skipped}, batch_size = #{batchSize},
                detail = CAST(#{detailJson} AS jsonb),
                elapsed_ms = #{elapsedMs}, finished_at = NOW(),
                updated_by = #{updatedBy}, updated_at = NOW()
            WHERE id = #{id}
            """)
    int markSuccess(@Param("id") Long id,
                    @Param("statusSuccess") String statusSuccess,
                    @Param("scannedCount") int scannedCount,
                    @Param("handled") int handled,
                    @Param("updated") int updated,
                    @Param("skipped") int skipped,
                    @Param("batchSize") Integer batchSize,
                    @Param("detailJson") String detailJson,
                    @Param("elapsedMs") long elapsedMs,
                    @Param("updatedBy") String updatedBy);

    /** 落 SKIPPED：什么都没做（锁被占用 / 总开关关闭）。 */
    @Update("""
            UPDATE capability_skill_sync_run
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

    /** 落 FAILED；异常随后照常上抛。 */
    @Update("""
            UPDATE capability_skill_sync_run
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

    @Select("""
            <script>
            SELECT id, trigger_type, status, scanned_count, handled, updated, skipped, batch_size,
                   CAST(detail AS text) AS detail,
                   skip_reason, error_message, elapsed_ms, started_at, finished_at, created_by,
                   (finished_at IS NULL AND started_at &lt; #{staleBefore}) AS stale
            FROM capability_skill_sync_run
            WHERE id = #{id}
            </script>
            """)
    SkillSyncRunRowVO getRun(@Param("id") Long id,
                             @Param("staleBefore") LocalDateTime staleBefore);

    @Select("""
            <script>
            SELECT id, trigger_type, status, scanned_count, handled, updated, skipped, batch_size,
                   CAST(detail AS text) AS detail,
                   skip_reason, error_message, elapsed_ms, started_at, finished_at, created_by,
                   (finished_at IS NULL AND started_at &lt; #{staleBefore}) AS stale
            FROM capability_skill_sync_run
            <where>
                <if test="triggerType != null">AND trigger_type = #{triggerType}</if>
                <if test="status != null">AND status = #{status}</if>
            </where>
            ORDER BY started_at DESC, id DESC
            </script>
            """)
    IPage<SkillSyncRunRowVO> pageRuns(Page<SkillSyncRunRowVO> page,
                                      @Param("triggerType") String triggerType,
                                      @Param("status") String status,
                                      @Param("staleBefore") LocalDateTime staleBefore);

    /** 记录表自身的保留策略：排除本轮刚写的记录。 */
    @Delete("""
            DELETE FROM capability_skill_sync_run
            WHERE created_at < #{cutoff} AND id <> #{keepId}
            """)
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("keepId") Long keepId);
}