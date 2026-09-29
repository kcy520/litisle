package com.litisle.commonredis.ratelimit;

import com.litisle.commonredis.config.RateLimitFallbackProperties;
import com.litisle.commonredis.guard.RedisGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

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
 * 【故障降级：三态熔断 + 本地兜底】
 * 限流是「辅助防线」，不应因自身故障阻断主流程；但纯 fail-open 意味着
 * 「Redis 挂 → 限流失效」，对防爆破类接口（登录/验证码）是防护缺口。
 * 故引入轻量三态熔断，故障期切换为 LocalRateLimiter 单机本地计数：
 * - CLOSED：正常走 Redis；连接/超时类失败连续达阈值 → OPEN
 *   （业务类异常如脚本返回 null 不算故障，不触发熔断）
 * - OPEN：冷却期内直接走本地兜底，不碰 Redis（避免每请求都吃连接超时）
 * - HALF_OPEN：冷却到期后放单个探测请求试 Redis，成功 → CLOSED，失败 → 回 OPEN
 *   （防冷却到期全量请求冲回 Redis 的惊群，也防一次侥幸成功就全量恢复）
 * 本地计数按 instance-count 做配额分摊（等效「本地阈值 = 集群阈值 ÷ 实例数」），
 * 保证降级期「更严或等严」，绝不更松——否则 N 实例各跑全量阈值，故障期实际
 * 放行 阈值×N，Redis 故障变成攻击窗口。日志只在熔断状态切换时打，天然节流防刷屏。
 *
 * 正常                     故障                     试探
 *   ┌──────────┐            ┌──────────┐            ┌──────────┐
 *   │ CLOSED   │  连续失败   │ OPEN     │  冷却到期   │ HALF_OPEN│
 *   │ 闭合·通路 │ ──────────→ │ 开路·断开 │ ──────────→ │ 半开·试探 │
 *   │ 走 Redis  │            │ 走本地    │            │ 1个探测  │
 *   └──────────┘            └──────────┘            └──────────┘
 *        ▲                                                │
 *        │           探测成功                               │ 探测失败
 *        └────────────────────────────────────────────────────┘
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

    /**
     * 本地兜底限流器（熔断 OPEN / 探测期单机计数，见 LocalRateLimiter）
     */
    private final LocalRateLimiter localRateLimiter;

    /**
     * 降级配置（enabled / cooldownSeconds / instanceCount，yml: litisle.rate-limit.fallback）
     */
    private final RateLimitFallbackProperties fallbackProperties;

    // ==================== 熔断三态（状态机已下沉 RedisGuard 公共组件，与 LoginRetryLimiter 共用） ====================

    /** 三态熔断守护（CLOSED/OPEN/HALF_OPEN 状态机，见 guard/RedisGuard） */
    private final RedisGuard redisGuard;

    public RedisRateLimiter(RedisTemplate<String, Object> redisTemplate,
                            LocalRateLimiter localRateLimiter,
                            RateLimitFallbackProperties fallbackProperties,
                            RedisGuard redisGuard) {
        this.redisTemplate = redisTemplate;
        this.localRateLimiter = localRateLimiter;
        this.fallbackProperties = fallbackProperties;
        this.redisGuard = redisGuard;
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

        // 步骤1：熔断调度（三态状态机已下沉 RedisGuard，与 LoginRetryLimiter 共用）
        // LOCAL=降级走本地兑底；PROBE=半开探测；REDIS=正常（含兑底总开关关闭）
        RedisGuard.Path path = redisGuard.acquire();
        if (path == RedisGuard.Path.LOCAL) {
            return localIncrement(key, windowSeconds);
        }
        // 步骤2：REDIS/PROBE 路径——走 Redis 计数（PROBE 为半开态放行的单个探测请求）
        return tryRedis(key, windowSeconds, path == RedisGuard.Path.PROBE);
    }

    /**
     * 执行一次 Redis Lua 固定窗口计数
     *
     * @param isProbe true 表示 HALF_OPEN 态的探测请求（成功→CLOSED 恢复，失败→回 OPEN）
     * @return 当前窗口累计次数；脚本无返回（业务类，非故障）返回 0；Redis 异常则计入熔断并走本地兑底
     */
    private long tryRedis(String key, long windowSeconds, boolean isProbe) {
        try {
            // 步骤1：执行 Lua 原子计数（INCR + 首次 EXPIRE）
            Long count = redisTemplate.execute(RATE_LIMIT_SCRIPT, Collections.singletonList(key), windowSeconds);
            // 步骤2：成功——上报守护（清零连续故障计数；探测请求成功则恢复 CLOSED）
            redisGuard.onSuccess(isProbe);
            // 步骤3：脚本无返回（业务类，非故障）→ 返回 0 兑底放行
            return count == null ? 0L : count;
        } catch (Exception e) {
            // 步骤4：异常——上报守护（探测失败回 OPEN / CLOSED 连续达阈值则熔断），转本地兑底
            redisGuard.onFailure(isProbe, e);
            return localIncrement(key, windowSeconds);
        }
    }

    // 熔断状态机成败上报已下沉 RedisGuard（onSuccess/onFailure，见 guard/RedisGuard）

    /**
     * 本地兑底计数：原始计数 × 实例数 = 集群等效计数
     * 等效于「本地阈值 = 集群阈值 ÷ 实例数」（向下取整只会更严不会更松），
     * 调用方仍拿返回值与集群阈值比较，increment 签名不变，调用方零改动。
     */
    private long localIncrement(String key, long windowSeconds) {
        // 步骤1：本地单机计数（原始值）
        // 步骤2：× 实例数 = 集群等效计数（Math.max(1,...) 防 instanceCount 误配为 0 导致恒放行）
            //本地只知道自己这一台机器收了多少请求，但调用方拿的是「集群总阈值」做比较。
            // 所以把本地计数 × N，假装成集群总计数返回，调用方用原来的阈值比较就能得到正确结果。
        return localRateLimiter.increment(key, windowSeconds)
                * Math.max(1, fallbackProperties.getInstanceCount());
    }

}
