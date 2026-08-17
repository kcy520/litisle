package com.litisle.commonredis.idempotent;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.resolver.UserIdResolver;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.RedisTemplate;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * 接口幂等性切面
 *
 * 【设计说明】
 * 1. 拦截标注 @Idempotent 的方法，在执行前校验是否重复提交
 * 2. 基于 Redis SETNX（setIfAbsent）实现分布式幂等
 * 3. 【方案 A】通过 UserIdResolver 接口获取 userId（可选注入），传给 KeyBuilder
 *
 * 【依赖倒置设计】
 * - common-redis 通过 UserIdResolver 接口获取 userId，不直接依赖认证框架
 * - 通过 common-core 的 UserIdResolver 接口解耦：
 *   - IdempotentAspect 依赖 UserIdResolver 接口（@Autowired required=false）
 *   - common-security 提供 SecurityContextUserIdResolver 实现
 *   - 业务服务同时引入两者时，Spring 自动注入实现
 *   - 业务服务只引入 common-redis 时，resolver 为 null，用 anonymous
 *
 * 【SETNX 原理】
 * - SETNX = SET if Not eXists
 * - Redis 原子操作：key 不存在则设置成功返回 true，已存在返回 false
 * - Spring Data Redis 封装为 redisTemplate.opsForValue().setIfAbsent(key, value, ttl)
 * - 原子性保证：即使多个请求同时到达，也只有一个能设置成功
 *
 * 【执行流程】
 * 1. 请求进入 → 切面拦截
 * 2. 通过 UserIdResolver 获取 userId（无实现则用 anonymous）
 * 3. 构建 key（userId + 接口路径 + 参数MD5）
 * 4. Redis SETNX，设置 TTL
 *    - true：首次请求，放行执行业务
 *    - false：重复请求，抛出异常
 *
 * 【面试要点】
 * - 依赖倒置原则（DIP）：高层依赖抽象，低层实现抽象
 * - @Autowired(required=false)：可选注入，保证 common-redis 可独立使用
 * - SETNX 的原子性：Spring Data Redis 的 setIfAbsent(key, value, timeout, unit) 是原子操作
 *
 * @author litisle
 */
@Slf4j
@Aspect
public class IdempotentAspect {

    /**
     * Redis 操作模板（由 common-redis 的 RedisConfig 注入）
     */
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 幂等 key 构建器
     */
    private final IdempotentKeyBuilder keyBuilder;

    /**
     * 用户ID 解析器（可选注入）
     * 【说明】
     * - 由 common-security 的 SecurityContextUserIdResolver 提供实现
     * - required=false：业务服务未引入 common-security 时为 null，用 anonymous
     * - 体现"可选依赖"设计，保证 common-redis 模块可独立工作
     */
    private UserIdResolver userIdResolver;

    public IdempotentAspect(RedisTemplate<String, Object> redisTemplate, IdempotentKeyBuilder keyBuilder) {
        this.redisTemplate = redisTemplate;
        this.keyBuilder = keyBuilder;
    }

    /**
     * 前置通知：校验幂等性
     *
     * @param joinPoint  连接点
     * @param idempotent 注解对象
     */
    @Before("@annotation(idempotent)")
    public void checkIdempotent(JoinPoint joinPoint, Idempotent idempotent) {
        // 1. 获取接口路径（类名:方法名，作为 key 的一部分）
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String methodPath = method.getDeclaringClass().getSimpleName() + ":" + method.getName();

        // 2. 获取当前用户ID（通过 UserIdResolver 接口，无实现则返回 null）
        Long userId = resolveUserId();

        // 3. 构建幂等 key（userId 由切面传入，KeyBuilder 不再自己获取）
        String key = keyBuilder.build(userId, methodPath, joinPoint.getArgs(), idempotent.includeArgs());

        // 4. 计算过期时间（统一转换为毫秒）
        long expireMillis = idempotent.timeUnit().toMillis(idempotent.expire());

        // 5. SETNX 原子操作：设置成功返回 true，已存在返回 false
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", expireMillis, TimeUnit.MILLISECONDS);

        // 6. 设置失败 → 重复提交
        if (Boolean.FALSE.equals(acquired)) {
            log.warn("幂等校验失败（重复提交）: key={}, method={}", key, methodPath);
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, idempotent.message());
        }

        log.debug("幂等校验通过: key={}, method={}", key, methodPath);
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