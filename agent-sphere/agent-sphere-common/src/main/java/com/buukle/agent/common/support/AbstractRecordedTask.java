package com.buukle.agent.common.support;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantUtil;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.LocalDateTime;

/**
 * 「长耗时后台任务」的通用骨架：抢锁 → 执行 → 异常留痕 → 释放锁。
 *
 * <p>抽出来是因为会话清理（{@code SessionCleanupTask}）与 Skill Hub 自动同步
 * （{@code SkillSyncRunner}）完全同构：都可能在多副本下重复执行、都耗时到分钟级、
 * 都需要执行记录回答「跑没跑、结果如何」。复制这套锁与异常语义，迟早在两处各自漂移。
 *
 * <p><b>记录先于锁写入</b>：「锁被占用」「急停开关关闭」这两种什么都没做的情况同样要留痕，
 * 否则事后无从解释「为什么这轮没跑」。所以插入记录由调用方在拿锁之前完成。
 *
 * <p>基类<b>不假设任何表结构</b>，子类只需实现记录读写与业务逻辑；但约定保持一致：
 * {@code trigger_type / status / started_at / finished_at / skip_reason / error_message}。
 * 这样前端的轮询面板也能按同一套形状写。
 *
 * @param <T> 一次执行的结果类型，由子类定义（会话清理是报告，同步是统计）
 * @param <C> 单次执行的上下文（会话清理是 {@code Boolean dryRun}）。
 *            <b>必须作为参数传递，不能存成字段</b>：执行体常跑在虚拟线程上，
 *            字段（哪怕 ThreadLocal）换个线程就读不到，会静默退化成默认值 ——
 *            对「预演 vs 真删」这种开关，退化方向是<b>真删</b>，后果不可逆。
 */
@Slf4j
public abstract class AbstractRecordedTask<T, C> {

    /** 定时任务没有请求上下文，记为 system（与 {@code AuditMetaObjectHandler} 的兜底一致）。 */
    protected static final String SYSTEM_OPERATOR = "system";

    /** 多副本互斥时的统一说辞。 */
    protected static final String SKIP_LOCK_REASON = "另一副本或另一次执行正在运行，本次跳过";

    /** 超过此时长仍是 RUNNING 就标记 stale：进程崩了，别让运维误读成「还在跑」。 */
    public static final long STALE_THRESHOLD_MINUTES = 30L;

    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    protected final RedissonClient redissonClient;

    protected AbstractRecordedTask(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 持锁执行：抢不到锁走 {@link #onLockBusy}，执行中抛异常则先落 FAILED 再上抛。
     *
     * <p>子类在 {@link #doRun} 内负责业务逻辑与落 SUCCESS/SKIPPED —— 它才持有判定所需的上下文。
     */
    protected final T runWithLock(C context, Long recordId, String operator, long startedAtMillis) {
        long elapsedBeforeLock = System.currentTimeMillis() - startedAtMillis;
        RLock lock = redissonClient.getLock(lockKey());
        if (!lock.tryLock()) {
            return onLockBusy(context, recordId, operator, elapsedBeforeLock);
        }
        try {
            return doRun(context, recordId, operator, startedAtMillis);
        } catch (Exception e) {
            // 异步入口下没人接收异常，完整堆栈必须在这里落日志
            markFailed(recordId, e, System.currentTimeMillis() - startedAtMillis, operator);
            log.error("{} failed: recordId={}", taskName(), recordId, e);
            throw e;
        } finally {
            lock.unlock();
        }
    }

    /** 抢不到锁时的默认行为：落 SKIPPED 并返回 null。需要返回业务对象的子类可覆盖。 */
    protected T onLockBusy(C context, Long recordId, String operator, long elapsedMillis) {
        markSkipped(recordId, SKIP_LOCK_REASON, elapsedMillis, operator);
        return null;
    }

    // ==================== 子类必须实现 ====================

    /** Redisson 锁键，建议落在 {@code scheduler:<name>} 命名空间下。 */
    protected abstract String lockKey();

    /** 插入 RUNNING 记录，返回主键。必须在拿锁之前调用。 */
    protected abstract Long insertRunning(C context, String operator);

    /** 真正的业务逻辑（已持锁）。子类负责在合适时机落 SUCCESS / SKIPPED。 */
    protected abstract T doRun(C context, Long recordId, String operator, long startedAtMillis);

    /** 落 SKIPPED：什么都没做，以及为什么。 */
    protected abstract void markSkipped(Long recordId, String reason, long elapsedMillis, String operator);

    /** 落 FAILED：记录异常摘要；基类随后会把异常继续上抛。 */
    protected abstract void markFailed(Long recordId, Exception e, long elapsedMillis, String operator);

    /** 任务名，仅用于日志。 */
    protected abstract String taskName();

    // ==================== 共用工具 ====================

    /**
     * 记录里的 operator。优先级与 {@code AuditMetaObjectHandler.currentUser()} 一致
     * （租户 → 登录用户 → system）。这里内联一份是因为那个 Handler 在 infrastructure，
     * common 与业务模块都拿不到它。
     */
    protected String currentOperator() {
        String tenant = TenantUtil.get();
        if (tenant != null && !tenant.isBlank()) {
            return tenant;
        }
        String auth = AuthContext.getUsername();
        if (auth != null && !auth.isBlank()) {
            return auth;
        }
        return SYSTEM_OPERATOR;
    }

    /** 「疑似中断」的判定时间线。 */
    protected LocalDateTime staleBefore() {
        return LocalDateTime.now().minusMinutes(STALE_THRESHOLD_MINUTES);
    }

    /** 异常信息截断，避免超长描述把记录表撑大。 */
    protected String abbreviate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_ERROR_MESSAGE_LENGTH
                ? message
                : message.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }
}