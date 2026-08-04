package com.litisle.commonredis.config;



import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 配置类
 * 
 * 【设计说明】
 * 1. Redisson 是 Redis 的 Java 客户端，提供了分布式锁、分布式集合等高级功能
 * 2. Jedis 是同步的、线程不安全的，需要连接池
 *    Lettuce 是异步的、线程安全的，Spring Boot 2.x 默认
 *    Redisson 提供了更多分布式特性（锁、计数器、信号量等）
 * 3. 本项目使用 Redisson 主要为了分布式锁
 * 
 * 【三种配置方式】
 * 1. 单机模式：redis://127.0.0.1:6379
 * 2. 哨兵模式：sentinel://...（主从切换）
 * 3. 集群模式：cluster://...（数据分片）
 * 
 * 【学习要点】
 * - RedissonClient 是线程安全的，全局一个实例即可
 * - 分布式锁原理：利用 Redis SETNX 命令 + Lua 脚本保证原子性
 * 
 * @author litisle
 */
@Configuration
@ConditionalOnClass(RedissonClient.class)
public class RedissonConfig {

    /**
     * Redis 地址（从 application.yml 读取）
     * 【格式】redis://127.0.0.1:6379
     * 【注意】必须加 redis:// 前缀，否则 Redisson 无法识别
     */
    @Value("${spring.data.redis.host:127.0.0.1}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    /**
     * 创建 Redisson 客户端
     * 
     * @return RedissonClient 实例
     */
    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();

        // 单机模式配置
        String address = "redis://" + host + ":" + port;
        config.useSingleServer()
                .setAddress(address)
                .setPassword(password.isEmpty() ? null : password)
                // 连接池大小（默认 64）
                .setConnectionPoolSize(64)
                // 最小空闲连接数（默认 32）
                .setConnectionMinimumIdleSize(32)
                // 连接超时（毫秒）
                .setConnectTimeout(10000)
                // 命令等待超时（毫秒）
                .setTimeout(3000)
                // 连接空闲超时（毫秒）
                .setIdleConnectionTimeout(60000);

        return Redisson.create(config);
    }
}