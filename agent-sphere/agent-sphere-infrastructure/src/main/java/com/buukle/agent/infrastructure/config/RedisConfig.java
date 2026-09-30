package com.buukle.agent.infrastructure.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class RedisConfig {

    /*
     * 注意：本项目的 Redis 访问统一走 Redisson（见下），不要引入 Spring Data Redis 的
     * StringRedisTemplate / RedisTemplate。
     *
     * 原因：本类的连接配置读的是 spring.redis.host/port（旧前缀，靠 @Value 显式读取），
     * 而 Spring Data Redis 的自动装配只认 spring.data.redis.*（Boot 3 已迁移）。
     * 在只配了旧前缀的情况下，自动装配会静默回落到 localhost:6379 —— 容器里没有本机 Redis，
     * 于是读写全部失败。曾因此导致任务级 MCP 凭证从未写入，且失败被降级成 warn 而难以定位。
     */

    @Value("${spring.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.redis.port:6379}")
    private int redisPort;

    @Bean
    @Primary
    public RedissonClient redissonClient() {
        return createClient(null);
    }

    /**
     * 事件总线专用客户端：JsonJacksonCodec（支持 {@link com.buukle.agent.runtime.kernel.port.vo.EventType}
     * 等 POJO 序列化/反序列化）。与默认客户端分离，避免影响 CacheService/锁/RBucket 的默认 codec。
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient eventBusRedissonClient() {
        return createClient(new JsonJacksonCodec());
    }

    private RedissonClient createClient(JsonJacksonCodec codec) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + redisPort)
                .setConnectionPoolSize(32);
        if (codec != null) {
            config.setCodec(codec);
        }
        return Redisson.create(config);
    }
}
