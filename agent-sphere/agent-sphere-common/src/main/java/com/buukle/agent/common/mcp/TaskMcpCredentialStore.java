package com.buukle.agent.common.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 任务级 MCP 凭证存储。
 *
 * <p>为什么放 Redis 而不是内存：backend 是 replicas=2，任务提交与工具执行可能落在不同副本上，
 * 进程内 Map 会随机失效（表现为「有时能去重，有时不能」这种最难查的偶发问题）。
 *
 * <p>为什么不存 agent_task 表：凭证会随 TaskVO 走接口返回，也会进各种查询视图，
 * 放进业务表等于把它铺到了所有读路径上。Redis + TTL 让它的生命周期与任务运行期严格对齐，
 * 任务结束或超时后自动消失。
 */
@Slf4j
@Component
public class TaskMcpCredentialStore {

    /** 默认 TTL：与 Bole 侧凭证有效期同量级，留出余量覆盖长任务。 */
    public static final Duration DEFAULT_TTL = Duration.ofHours(2);

    private static final String KEY_PREFIX = "runtime:mcp:task-cred:";

    private final StringRedisTemplate redisTemplate;

    public TaskMcpCredentialStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 提交任务时登记凭证。凭证为空表示该任务不需要 MCP 凭证，直接跳过。 */
    public void put(Long sessionId, String credential) {
        if (sessionId == null || credential == null || credential.isBlank()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key(sessionId), credential, DEFAULT_TTL);
        } catch (Exception e) {
            // 登记失败不能阻断任务：没有凭证只是 MCP 查重不可用，寻访本身仍能跑完。
            log.warn("Failed to store task MCP credential for sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    /** 工具执行时按 session 取凭证；取不到返回 null，调用方按「无凭证」处理。 */
    public String get(Long sessionId) {
        if (sessionId == null) {
            return null;
        }
        try {
            return redisTemplate.opsForValue().get(key(sessionId));
        } catch (Exception e) {
            log.warn("Failed to read task MCP credential for sessionId={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /** 任务终态时清理，避免凭证在 Redis 里多活到 TTL 结束。 */
    public void evict(Long sessionId) {
        if (sessionId == null) {
            return;
        }
        try {
            redisTemplate.delete(key(sessionId));
        } catch (Exception e) {
            log.warn("Failed to evict task MCP credential for sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    private String key(Long sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
