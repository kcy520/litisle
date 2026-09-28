package com.litisle.commonredis.ratelimit;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.resolver.UserIdResolver;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.TimeUnit;


/**
 * 接口限流切面
 *
 * 【设计说明】
 * 1. 拦截标注 @RateLimiter 的方法，在执行前校验请求频率
 * 2. 基于 Redis + Lua 脚本实现分布式限流（固定窗口计数法）
 * 3. 【依赖倒置】通过 UserIdResolver 接口获取 userId（可选注入），与 IdempotentAspect 一致
 *
 * 【Lua 脚本说明】（脚本实体见 RedisRateLimiter#increment，切面与全站 QPS 拦截器共用）
 * local count = redis.call('incr', KEYS[1])
 * if count == 1 then
 *     redis.call('expire', KEYS[1], ARGV[1])
 * end
 * return count
 *
 * - INCR：原子自增（key 不存在时自动创建并设为 1）
 * - 首次访问（count == 1）才设置 EXPIRE，避免每次请求都设过期
 * - Lua 脚本在 Redis 中单线程执行，保证 INCR + EXPIRE 的原子性
 *
 * 【为什么不用 INCR + 单独 EXPIRE】
 * - 非原子操作：并发下可能 INCR 成功但 EXPIRE 失败，导致 key 永久存在
 * - 后续所有请求都被拒绝（count 无限增长），限流永久生效
 *
 * 【执行流程】
 * 1. 请求进入 → 切面拦截
 * 2. 根据 keyType 构建 key（USER/IP/DEFAULT）
 * 3. 执行 Lua 脚本，返回当前计数
 * 4. count <= maxCount：放行
 * 5. count > maxCount：抛出 BizException(TOO_MANY_REQUESTS)
 *
 * 【面试要点】
 * - 分布式限流：基于 Redis，多节点共享计数
 * - Lua 脚本原子性：Redis 单线程执行，避免并发问题
 * - 依赖倒置：通过 UserIdResolver 解耦 common-redis 与 common-security
 * - 固定窗口 vs 滑动窗口：固定窗口实现简单但有边界问题，滑动窗口精确但复杂
 *
 * @author litisle
 */
@Slf4j
@Aspect
public class RateLimiterAspect {

    /**
     * Redis 固定窗口限流器（Lua 脚本已抽取至该可复用组件，与全站 QPS 拦截器共用同一脚本）
     */
    private final RedisRateLimiter redisRateLimiter;

    /**
     * 限流 key 构建器
     */
    private final RateLimiterKeyBuilder keyBuilder;

    /**
     * 用户ID 解析器（可选注入）
     * 【说明】
     * - 由 common-security 的 SecurityContextUserIdResolver 提供实现
     * - required=false：业务服务未引入 common-security 时为 null，USER 类型降级为 anonymous
     * - 与 IdempotentAspect 保持一致的依赖倒置设计
     */
    private UserIdResolver userIdResolver;

    public RateLimiterAspect(RedisRateLimiter redisRateLimiter, RateLimiterKeyBuilder keyBuilder) {
        this.redisRateLimiter = redisRateLimiter;
        this.keyBuilder = keyBuilder;
    }

    /**
     * 前置通知：执行限流校验
     *
     * @param joinPoint   连接点
     * @param rateLimiter 注解对象
     */
    @Before("@annotation(rateLimiter)")
    public void checkRateLimit(JoinPoint joinPoint, RateLimiter rateLimiter) {
        // 1. 获取目标方法
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // 2. 获取当前用户ID（通过 UserIdResolver 接口，无实现则返回 null）
        Long userId = resolveUserId();

        // 3. 构建限流 key
        String key = keyBuilder.build(rateLimiter.keyType(), userId, userIdResolver, method);

        // 4. 计算时间窗口（统一转换为秒，作为 Lua 脚本的 ARGV[1]）
        long windowSeconds = rateLimiter.timeUnit().toSeconds(rateLimiter.time());

        // 5. 执行固定窗口计数（Lua 原子操作：INCR + 首次 EXPIRE，见 RedisRateLimiter）
        //    注意：windowSeconds 必须传数字类型，不能转成 String。
        //    JSON 序列化器会把 String 再包一层引号（"60"），导致 Redis EXPIRE 收到非整数报错。
        long currentCount = redisRateLimiter.increment(key, windowSeconds);

        // 6. 限流判断
        if (currentCount > rateLimiter.count()) {
            log.warn("限流触发: key={}, currentCount={}, maxCount={}, method={}",
                    key, currentCount, rateLimiter.count(),
                    method.getDeclaringClass().getSimpleName() + ":" + method.getName());
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, rateLimiter.message());
        }

        log.debug("限流校验通过: key={}, currentCount={}, maxCount={}",
                key, currentCount, rateLimiter.count());
    }

    /**
     * 通过 UserIdResolver 获取当前用户ID
     * 【说明】封装为独立方法，便于扩展和测试
     *
     * @return 用户ID，无 UserIdResolver 实现时返回 null
     */
    /**
     * 设置 UserIdResolver（可选依赖）
     * 由 CommonRedisAutoConfiguration 通过 ObjectProvider 注入
     */
    public void setUserIdResolver(UserIdResolver userIdResolver) {
        this.userIdResolver = userIdResolver;
    }

    private Long resolveUserId() {
        if (userIdResolver == null) {
            return null;
        }
        try {
            return userIdResolver.getUserId();
        } catch (Exception e) {
            log.warn("UserIdResolver 获取用户ID失败，使用匿名标识: {}", e.getMessage());
            return null;
        }
    }
}