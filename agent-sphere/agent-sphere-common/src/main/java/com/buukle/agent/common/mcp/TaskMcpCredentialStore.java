package com.buukle.agent.common.mcp;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 任务级 MCP 凭证存储。
 *
 * <p>为什么放 Redis 而不是内存：backend 是 replicas=2，任务提交与工具执行可能落在不同副本上，
 * 进程内 Map 会随机失效（表现为「有时能去重，有时不能」这种最难查的偶发问题）。
 *
 * <p>为什么不存 agent_task 表：凭证会随 TaskVO 走接口返回，也会进各种查询视图，
 * 放进业务表等于把它铺到了所有读路径上。Redis + TTL 让它的生命周期与任务运行期严格对齐。
 *
 * <p><b>为什么用 Redisson 而不是 StringRedisTemplate</b>：本项目的 Redis 连接**只由**
 * infrastructure 的 {@code RedisConfig} 手工创建，其配置读的是 {@code spring.redis.host/port}
 * （旧前缀）。而 Spring Data Redis 的自动装配只认 {@code spring.data.redis.*}，
 * 在只有旧前缀的情况下会静默回落到 {@code localhost:6379} —— 容器里没有本机 Redis，
 * 于是读写全部失败。用 Redisson 就与 CacheService / 锁 / 事件总线走**同一条已验证的连接**，
 * 不再依赖 Boot 的属性前缀。同理，本类也刻意不碰 {@code @Value("${spring.redis...}")}。
 */
@Slf4j
@Component
public class TaskMcpCredentialStore {

    /** 默认 TTL：调用方未显式指定时使用（任务链路会按「任务超时 + 余量」传入，见 AgentTaskServiceImpl）。 */
    public static final Duration DEFAULT_TTL = Duration.ofHours(2);

    private static final String KEY_PREFIX = "runtime:mcp:task-cred:";

    private final RedissonClient redissonClient;

    public TaskMcpCredentialStore(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /** 提交任务时登记凭证，使用 {@link #DEFAULT_TTL}。凭证为空表示该任务不需要 MCP 凭证，直接跳过。 */
    public void put(Long sessionId, String credential) {
        put(sessionId, credential, DEFAULT_TTL);
    }

    /**
     * 提交任务时按指定 TTL 登记凭证。
     *
     * <p>任务链路的 TTL 必须 ≥ 业务方为同一任务签发的 JWT 有效期：Redis 先过期会退化成
     * 报「缺少凭证」（而非「凭证过期」），结论失真且极难排查。调用方务必留足余量。
     */
    public void put(Long sessionId, String credential, Duration ttl) {
        if (sessionId == null || credential == null || credential.isBlank()) {
            return;
        }
        try {
            bucket(sessionId).set(credential, ttl != null ? ttl : DEFAULT_TTL);
        } catch (Exception e) {
            // 登记失败不能阻断任务（寻访本身仍能跑完），但**必须大声报错**：
            // 凭证没写进去 = 后续所有 MCP 调用都会因"缺少凭证"被拒，而下游报错离本侧根因很远。
            // 这里以前只打 warn，正是它让「凭证链路断了」在生产上完全静默。
            log.error("[MCP] 任务级凭证写入 Redis 失败，该任务的 MCP 查重将不可用 sessionId={}", sessionId, e);
        }
    }

    /** 工具执行时按 session 取凭证；取不到返回 null，调用方按「无凭证」处理。 */
    public String get(Long sessionId) {
        if (sessionId == null) {
            return null;
        }
        try {
            return bucket(sessionId).get();
        } catch (Exception e) {
            log.error("[MCP] 任务级凭证读取 Redis 失败，本次 MCP 调用将不带凭证 sessionId={}", sessionId, e);
            return null;
        }
    }

    /** 任务终态时清理，避免凭证在 Redis 里多活到 TTL 结束。 */
    public void evict(Long sessionId) {
        if (sessionId == null) {
            return;
        }
        try {
            bucket(sessionId).delete();
        } catch (Exception e) {
            log.warn("[MCP] 任务级凭证清理失败（不影响主流程，TTL 会兜底） sessionId={}", sessionId, e);
        }
    }

    private RBucket<String> bucket(Long sessionId) {
        return redissonClient.getBucket(KEY_PREFIX + sessionId);
    }
}
