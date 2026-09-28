package com.litisle.commonredis.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;

/**
 * Redis 固定窗口限流器（可复用组件）
 *
 * 【为什么单独抽这个类】
 * 原先 Lua 脚本内联在 RateLimiterAspect 中，属于「单一事实来源」破坏点：
 * 注解式限流（@RateLimiter）与系统级限流（如 FileGlobalQpsInterceptor）都需要同一套
 * 「INCR + 首次 EXPIRE」原子计数逻辑，抽取后共用同一脚本，避免两处脚本漂移。
 *
 * 【使用场景】
 * 1. 注解式接口限流：RateLimiterAspect 依据 @RateLimiter 元数据调用本组件计数
 * 2. 系统级/全站限流：拦截器或过滤器对某类路径做总量上限（如 /api/file/** 全站 QPS）
 * 3. 任意需要「固定窗口计数」的业务：给 key + 窗口秒数即可
 *
 * 【选型速查表】
 * | 需求                            | 推荐做法                                             |
 * |---------------------------------|------------------------------------------------------|
 * | 单接口按用户/IP 限流            | @RateLimiter(keyType=USER/IP)，走 RateLimiterAspect  |
 * | 单接口全站总量限流              | 本组件 + 业务拦截器（注解不可重复，故用拦截器）      |
 * | 多接口合计总量限流（全站 QPS）  | 本组件 + 按路径注册拦截器，key 统一                  |
 * | 需要精确滑动窗口                | 不适用（本组件为固定窗口，窗口边界存在突刺）         |
 *
 * 【Lua 脚本说明】（脚本实体见下方常量，切面与系统级拦截器共用）
 * local count = redis.call('incr', KEYS[1])
 * if count == 1 then
 *     redis.call('expire', KEYS[1], ARGV[1])
 * end
 * return count
 * - INCR：原子自增（key 不存在时自动创建并设为 1）
 * - 首次访问（count == 1）才设置 EXPIRE，避免每次请求都设过期
 * - Lua 脚本在 Redis 中单线程执行，保证 INCR + EXPIRE 的原子性
 *
 * 【为什么不用 INCR + 单独 EXPIRE】
 * - 非原子操作：并发下可能 INCR 成功但 EXPIRE 失败，导致 key 永久存在
 * - 后续所有请求都被拒绝（count 无限增长），限流永久生效
 *
 * 【降级策略：fail-open】
 * 限流是「辅助防线」，不应因自身故障阻断主流程。故本组件统一降级语义：
 * - Redis 不可用/脚本异常 → 捕获后返回 0（放行），打 warn 日志，调用方视为未超限
 * - 脚本无返回（null） → 同样返回 0（放行）
 * 即「限流组件故障 = 不限制」，优先保业务可用性；代价是故障期间失去限流保护。
 *
 * @author litisle
 */
@Slf4j
public class RedisRateLimiter {

    /**
     * 固定窗口计数脚本（单一事实来源：@RateLimiter 切面与系统级限流拦截器共用）
     */
    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT = new DefaultRedisScript<>(
            "local count = redis.call('incr', KEYS[1])\n"
                    + "if count == 1 then\n"
                    + "    redis.call('expire', KEYS[1], ARGV[1])\n"
                    + "end\n"
                    + "return count",
            Long.class);

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisRateLimiter(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 执行一次固定窗口计数并返回当前窗口内累计次数
     *
     * @param key           限流 key（调用方按维度构建，如 rate_limit:user:{id}:... / rate_limit:global:file:qps）
     * @param windowSeconds 窗口大小（秒），作为 Lua 的 ARGV[1]
     * @return 当前窗口累计次数（首次为 1）；脚本无返回或 Redis 异常时返回 0（fail-open 放行，见类注释）
     */
    public long increment(String key, long windowSeconds) {
        // 注意：windowSeconds 必须传数字类型，不能转成 String。
        // JSON 序列化器会把 String 再包一层引号（"60"），导致 Redis EXPIRE 收到非整数报错。
        try {
            Long count = redisTemplate.execute(RATE_LIMIT_SCRIPT, Collections.singletonList(key), windowSeconds);
            return count == null ? 0L : count;
        } catch (Exception e) {
            // 降级：限流组件故障（如 Redis 不可用）时放行，避免辅助防线拖垮主流程（fail-open）
            log.warn("限流计数失败，降级放行(fail-open): key={}, windowSeconds={}, error={}",
                    key, windowSeconds, e.getMessage());
            return 0L;
        }
    }
}
