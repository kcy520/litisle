package com.litisle.commonredis.config;

import com.litisle.commoncore.resolver.UserIdResolver;
import com.litisle.commonredis.bloom.BloomFilter;
import com.litisle.commonredis.guard.RedisGuardMetrics;
import com.litisle.commonredis.lock.DistributedLock;
import com.litisle.commonredis.idempotent.IdempotentAspect;
import com.litisle.commonredis.idempotent.IdempotentKeyBuilder;
import com.litisle.commonredis.lock.RedissonLock;
import com.litisle.commonredis.ratelimit.LocalRateLimiter;
import com.litisle.commonredis.ratelimit.RateLimiterAspect;
import com.litisle.commonredis.ratelimit.RateLimiterKeyBuilder;
import com.litisle.commonredis.ratelimit.RedisRateLimiter;
import com.litisle.commonredis.util.RedisUtil;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * common-redis 核心自动配置
 *
 * 【设计说明】
 * 集中注册所有 Bean，替代 @Component 组件扫描，确保作为库模块时 Bean 一定被注册。
 * 消费方只需引入 common-redis 依赖 + AutoConfiguration.imports，无需额外配置组件扫描路径。
 *
 * 设计原则（与 common-security 的 CommonSecurityAutoConfiguration 一致）：
 * 1. 所有 @Component 类改为 @Bean 方法注册，消费方无需 @ComponentScan
 * 2. 使用 @ConditionalOnMissingBean，允许消费方覆盖默认实现
 * 3. 对于 @Autowired(required=false) 的可选依赖，使用 ObjectProvider 处理
 *
 * @author litisle
 */
@Configuration
@EnableConfigurationProperties(RateLimitFallbackProperties.class)//把 RateLimitFallbackProperties 这个 @ConfigurationProperties 类注册成 Bean，并自动绑定 yml / properties 里的配置项
public class CommonRedisAutoConfiguration {

    /** 熔断器实例名：RedisRateLimiter 按该名称获取状态机 */
    private static final String REDIS_GUARD_INSTANCE_NAME = "redisGuard";

    // ==================== 工具类 ====================

    /**
     * Redis 操作工具类
     */
    @Bean
    @ConditionalOnMissingBean
    public RedisUtil redisUtil(RedisTemplate<String, Object> redisTemplate,
                               StringRedisTemplate stringRedisTemplate) {
        return new RedisUtil(redisTemplate, stringRedisTemplate);
    }

    /**
     * 幂等 Key 构建器
     */
    @Bean
    @ConditionalOnMissingBean
    public IdempotentKeyBuilder idempotentKeyBuilder() {
        return new IdempotentKeyBuilder();
    }

    /**
     * 限流 Key 构建器
     */
    @Bean
    @ConditionalOnMissingBean
    public RateLimiterKeyBuilder rateLimiterKeyBuilder() {
        return new RateLimiterKeyBuilder();
    }

    // ==================== 服务类 ====================

    /**
     * 布隆过滤器封装
     */
    @Bean
    @ConditionalOnMissingBean
    public BloomFilter bloomFilter(RedissonClient redissonClient) {
        return new BloomFilter(redissonClient);
    }

    /**
     * 分布式锁（Redisson 实现）
     */
    @Bean
    @ConditionalOnMissingBean
    public RedissonLock redissonLock(RedissonClient redissonClient) {
        return new RedissonLock(redissonClient);
    }

    // ==================== 切面类 ====================

    /**
     * 幂等性校验切面
     *
     * UserIdResolver 为可选依赖（通过 ObjectProvider 获取），
     * 业务服务未引入 common-security 时，resolver 为 null，降级为 anonymous
     */
    @Bean
    @ConditionalOnMissingBean
    public IdempotentAspect idempotentAspect(
            RedisTemplate<String, Object> redisTemplate,
            IdempotentKeyBuilder keyBuilder,
            ObjectProvider<UserIdResolver> userIdResolverProvider) {
        IdempotentAspect aspect = new IdempotentAspect(redisTemplate, keyBuilder);
        UserIdResolver resolver = userIdResolverProvider.getIfAvailable();
        if (resolver != null) {
            aspect.setUserIdResolver(resolver);
        }
        return aspect;
    }

    /**
     * Redis 熔断器配置（显式等价映射自研「连续 3 次失败即开」口径）
     *
     * 【为什么必须显式配置，不能吃 Resilience4j 默认值】
     * Resilience4j 默认是「滑动窗口失败率 50% / 最小调用数 100 次」；
     * 原自研降级实现是「连续 3 次失败即 OPEN」。若沿用默认值，
     * Redis 连挂 3 次也不会跳闸，故障期会持续吃连接超时拖垮接口延迟。
     * 因此三项必须一起显式对齐：失败率 100%（窗口内全失败）+ 窗口 3 次 + 最小调用数 3 次。
     */
    @Bean
    @ConditionalOnMissingBean
    public CircuitBreakerConfig redisGuardCircuitBreakerConfig(RateLimitFallbackProperties fallbackProperties) {
        return createCircuitBreakerConfig(fallbackProperties);
    }

    /**
     * 构建熔断器配置（静态工厂，供测试直接断言生产配置值，防止错误配置上线）
     *
     * 【等价映射说明】
     * - failureRateThreshold=100：窗口内失败率达到 100% 才打开（等价「窗口内全部失败」）
     * - slidingWindowSize=3：滑动窗口只保留最近 3 次调用
     * - minimumNumberOfCalls=3：前 3 次调用不计算失败率（避免首次失败就打开）
     * - waitDurationInOpenState=cooldownSeconds：沿用 litisle.rate-limit.fallback.cooldown-seconds
     * - permittedNumberOfCallsInHalfOpenState=1：半开仅放行 1 个探测许可（防惊群）
     * - automaticTransitionFromOpenToHalfOpenEnabled=true：冷却到期自动转半开；
     *   若为 false 且半开许可配 0，会出现降级后永不恢复
     *
     * @param fallbackProperties 降级配置（读取 cooldownSeconds）
     * @return Resilience4j 熔断器配置
     */
    public static CircuitBreakerConfig createCircuitBreakerConfig(RateLimitFallbackProperties fallbackProperties) {
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(100)
                .slidingWindowSize(3)
                .minimumNumberOfCalls(3)
                .waitDurationInOpenState(Duration.ofSeconds(fallbackProperties.getCooldownSeconds()))
                .permittedNumberOfCallsInHalfOpenState(1)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
    }

    /**
     * Redis 熔断器注册中心（同时完成同名指标桥接）
     *
     * @param circuitBreakerConfig       熔断器配置（显式等价映射）
     * @param meterRegistryProvider      MeterRegistry 可选依赖：未引 actuator 的服务为 null
     * @return 熔断器注册中心
     */
    @Bean
    @ConditionalOnMissingBean
    public CircuitBreakerRegistry redisGuardCircuitBreakerRegistry(
            CircuitBreakerConfig circuitBreakerConfig,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        // 单一实例名：RedisRateLimiter 通过该名称获取唯一熔断器实例
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(circuitBreakerConfig);
        CircuitBreaker circuitBreaker = registry.circuitBreaker(REDIS_GUARD_INSTANCE_NAME);
        // ObjectProvider 可选注入（绝不能改成构造注入强依赖）：无 actuator 时指标静默停用，状态机照常工作
        RedisGuardMetrics.register(circuitBreaker, meterRegistryProvider.getIfAvailable());
        return registry;
    }

    /**
     * Redis 熔断器实例（RedisRateLimiter 直接注入它调编程式 API）
     *
     * @param registry 熔断器注册中心
     * @return redisGuard 实例（所有消费方共享同一状态机）
     */
    @Bean
    @ConditionalOnMissingBean
    public CircuitBreaker redisGuardCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker(REDIS_GUARD_INSTANCE_NAME);
    }

    /**
     * 本地兑底限流器（单机固定窗口计数，Redis 故障降级期使用）
     */
    @Bean
    @ConditionalOnMissingBean
    public LocalRateLimiter localRateLimiter() {
        return new LocalRateLimiter();
    }

    /**
     * Redis 固定窗口限流器（可复用组件）
     *
     * 抽取自原 RateLimiterAspect 的 Lua 脚本，供 @RateLimiter 切面与业务侧
     * 系统级限流（如 FileGlobalQpsInterceptor）共用，保证单一事实来源；
     * 内置三态熔断，Redis 故障时自动切换本地兑底（见 RedisRateLimiter 类注释）
     */
    @Bean
    @ConditionalOnMissingBean
    public RedisRateLimiter redisRateLimiter(RedisTemplate<String, Object> redisTemplate,
                                             LocalRateLimiter localRateLimiter,
                                             RateLimitFallbackProperties fallbackProperties,
                                             CircuitBreaker redisGuardCircuitBreaker) {
        return new RedisRateLimiter(redisTemplate, localRateLimiter, fallbackProperties, redisGuardCircuitBreaker);
    }

    /**
     * 接口限流切面
     *
     * UserIdResolver 为可选依赖，设计同上
     */
    @Bean
    @ConditionalOnMissingBean
    public RateLimiterAspect rateLimiterAspect(
            RedisRateLimiter redisRateLimiter,
            RateLimiterKeyBuilder keyBuilder,
            ObjectProvider<UserIdResolver> userIdResolverProvider) {
        RateLimiterAspect aspect = new RateLimiterAspect(redisRateLimiter, keyBuilder);
        UserIdResolver resolver = userIdResolverProvider.getIfAvailable();
        if (resolver != null) {
            aspect.setUserIdResolver(resolver);
        }
        return aspect;
    }
}