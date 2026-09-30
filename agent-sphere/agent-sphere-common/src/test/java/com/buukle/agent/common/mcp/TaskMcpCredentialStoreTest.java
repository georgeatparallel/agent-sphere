package com.buukle.agent.common.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 任务级凭证存储。
 *
 * <p>这组断言守护两件曾经出过事的事情：
 * <ol>
 *   <li><b>连接必须走 Redisson</b>。此前用 {@code StringRedisTemplate}，而本项目的 Redis 配置写在旧前缀
 *       {@code spring.redis.*}（Spring Boot 3 的自动装配只认 {@code spring.data.redis.*}），
 *       结果连接静默回落到 {@code localhost:6379}，凭证从未写入 —— 表现为下游报
 *       「缺少 X-Task-Mcp-Credential」，与真正的根因相隔极远。</li>
 *   <li><b>失败不得外抛</b>。Redis 抖动不该让寻访任务失败（MCP 只是增强），
 *       但必须留下 ERROR 级日志，不能像之前那样静默。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class TaskMcpCredentialStoreTest {

    private static final Long SESSION_ID = 42L;
    private static final String KEY = "runtime:mcp:task-cred:42";

    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RBucket<String> bucket;

    private TaskMcpCredentialStore store;

    @BeforeEach
    void setUp() {
        store = new TaskMcpCredentialStore(redissonClient);
    }

    @Test
    void put_storesCredentialUnderSessionKeyWithTtl() {
        when(redissonClient.<String>getBucket(KEY)).thenReturn(bucket);

        store.put(SESSION_ID, "cred-token");

        verify(bucket).set("cred-token", TaskMcpCredentialStore.DEFAULT_TTL);
    }

    /**
     * 任务链路按「任务超时 + 余量」传入 TTL；若被忽略退回固定 2h，长任务（上限 4h）
     * 会出现「任务还在跑、Redis 里的凭证先没了」。
     */
    @Test
    void put_withExplicitTtl_usesThatTtl() {
        when(redissonClient.<String>getBucket(KEY)).thenReturn(bucket);
        java.time.Duration ttl = java.time.Duration.ofMinutes(20);

        store.put(SESSION_ID, "cred-token", ttl);

        verify(bucket).set("cred-token", ttl);
    }

    @Test
    void put_blankCredentialIsNoOp() {
        store.put(SESSION_ID, "   ");
        store.put(SESSION_ID, null);
        store.put(null, "cred-token");

        verifyNoInteractions(redissonClient);
    }

    @Test
    void get_returnsStoredCredential() {
        when(redissonClient.<String>getBucket(KEY)).thenReturn(bucket);
        when(bucket.get()).thenReturn("cred-token");

        assertEquals("cred-token", store.get(SESSION_ID));
    }

    @Test
    void get_returnsNullWhenRedisUnavailable() {
        when(redissonClient.<String>getBucket(anyString()))
                .thenThrow(new IllegalStateException("Unable to connect to Redis server: localhost/127.0.0.1:6379"));

        // 取不到就返回 null（调用方按「无凭证」处理），绝不让异常打断任务
        assertNull(store.get(SESSION_ID));
    }

    @Test
    void put_failureDoesNotPropagate() {
        when(redissonClient.<String>getBucket(anyString()))
                .thenThrow(new IllegalStateException("Unable to connect to Redis server"));

        assertDoesNotThrow(() -> store.put(SESSION_ID, "cred-token"));
    }

    @Test
    void evict_failureDoesNotPropagate() {
        when(redissonClient.<String>getBucket(anyString()))
                .thenThrow(new IllegalStateException("Unable to connect to Redis server"));

        assertDoesNotThrow(() -> store.evict(SESSION_ID));
    }

    /**
     * 防回归：只允许依赖 Redisson，不得再出现 Spring Data Redis 类型。
     * 一旦有人改回 {@code StringRedisTemplate}，这条会立刻失败并指向上面的类注释。
     */
    @Test
    void dependsOnRedissonOnly() {
        Constructor<?> constructor = TaskMcpCredentialStore.class.getConstructors()[0];
        assertEquals(RedissonClient.class, constructor.getParameterTypes()[0]);

        boolean anySpringDataRedisField = Arrays.stream(TaskMcpCredentialStore.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .anyMatch(f -> f.getType().getName().startsWith("org.springframework.data.redis"));
        assertTrue(!anySpringDataRedisField,
                "不得使用 Spring Data Redis 类型：本项目的 Redis 连接由 Redisson 统一管理");
    }
}
