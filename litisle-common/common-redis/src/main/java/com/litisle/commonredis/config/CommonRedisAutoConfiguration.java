package com.litisle.commonredis.config;

import com.litisle.commoncore.resolver.UserIdResolver;
import com.litisle.commonredis.bloom.BloomFilter;
import com.litisle.commonredis.lock.DistributedLock;
import com.litisle.commonredis.login.LoginRetryLimiter;
import com.litisle.commonredis.idempotent.IdempotentAspect;
import com.litisle.commonredis.idempotent.IdempotentKeyBuilder;
import com.litisle.commonredis.lock.RedissonLock;
import com.litisle.commonredis.ratelimit.LocalRateLimiter;
import com.litisle.commonredis.ratelimit.RateLimiterAspect;
import com.litisle.commonredis.ratelimit.RateLimiterKeyBuilder;
import com.litisle.commonredis.ratelimit.RedisRateLimiter;
import com.litisle.commonredis.util.RedisUtil;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

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
     * 登录重试限制器
     *
     * 防止暴力破解，记录连续登录失败次数，超限后临时锁定账户
     * litisle-auth 在登录校验时手动调用
     */
    @Bean
    @ConditionalOnMissingBean
    public LoginRetryLimiter loginRetryLimiter(StringRedisTemplate stringRedisTemplate) {
        return new LoginRetryLimiter(stringRedisTemplate);
    }

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
     * Redis 固定窗口限流器（可复用组件）
     *
     * 抽取自原 RateLimiterAspect 的 Lua 脚本，供 @RateLimiter 切面与业务侧
     * 系统级限流（如 FileGlobalQpsInterceptor）共用，保证单一事实来源
     */
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
                                             RateLimitFallbackProperties fallbackProperties) {
        return new RedisRateLimiter(redisTemplate, localRateLimiter, fallbackProperties);
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