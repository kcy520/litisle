package com.litisle.commonredis.ratelimit;

import com.litisle.commonredis.config.RateLimitFallbackProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

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
 * 【故障降级：Resilience4j 熔断 + 本地兜底】
 * 限流是「辅助防线」，不应因自身故障阻断主流程；但纯 fail-open 意味着
 * 「Redis 挂 → 限流失效」，对防爆破类接口（登录/验证码）是防护缺口。
 * 故引入轻量熔断（Resilience4j CircuitBreaker，编程式 API），故障期切换为
 * LocalRateLimiter 单机本地计数：
 * - CLOSED：正常走 Redis；连续 3 次失败 → OPEN
 *   （业务类异常如脚本返回 null 按成功处理，不触发熔断）
 * - OPEN：冷却期内直接走本地兜底，不碰 Redis（避免每请求都吃连接超时）
 * - HALF_OPEN：冷却到期后自动进入，仅放行 1 个探测许可，成功 → CLOSED，失败 → 回 OPEN
 *   （防冷却到期全量请求冲回 Redis 的惊群，也防一次侥幸成功就全量恢复）
 * 本地计数按 instance-count 做配额分摊（等效「本地阈值 = 集群阈值 ÷ 实例数」），
 * 保证降级期「更严或等严」，绝不更松——否则 N 实例各跑全量阈值，故障期实际
 * 放行 阈值×N，Redis 故障变成攻击窗口。日志只在熔断状态切换时打，天然节流防刷屏。
 *
 * 【为什么用编程式 API 而不是 @CircuitBreaker 注解】
 * increment 内部对 tryRedis 的调用是**同类自调用**，不走 Spring AOP 代理；
 * 挂注解会静默失效且不报错（Redis 连挂也打不开熔断），因此统一用编程式
 * tryAcquirePermission / onSuccess / onError。
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

    // ==================== 熔断三态（状态机由 Resilience4j CircuitBreaker 承载，与 LoginRetryLimiter 共用同一实例） ====================

    /**
     * 熔断器（CLOSED/OPEN/HALF_OPEN 状态机）
     *
     * 【为什么直接注入 CircuitBreaker 而不是 RedisGuard】
     * 本类只需「申请许可 + 上报成败」两个动作，直接调 Resilience4j 编程式 API；
     * RedisGuard 保留给 LoginRetryLimiter 的既有签名（薄适配层，见 guard/RedisGuard）。
     * 两者注入的是同一个 CircuitBreaker 单例，降级状态天然共享。
     */
    private final CircuitBreaker circuitBreaker;

    /**
     * 构造限流器
     *
     * @param redisTemplate      Redis 模板（承载固定窗口计数 Lua 脚本）
     * @param localRateLimiter   本地兜底限流器（熔断 OPEN / 半开探测期使用）
     * @param fallbackProperties 降级配置（enabled 开关 / instanceCount 配额分摊）
     * @param circuitBreaker     熔断器（三态状态机）
     */
    public RedisRateLimiter(RedisTemplate<String, Object> redisTemplate,
                            LocalRateLimiter localRateLimiter,
                            RateLimitFallbackProperties fallbackProperties,
                            CircuitBreaker circuitBreaker) {
        this.redisTemplate = redisTemplate;
        this.localRateLimiter = localRateLimiter;
        this.fallbackProperties = fallbackProperties;
        this.circuitBreaker = circuitBreaker;
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

        // 步骤1：兜底总开关关闭——跳过熔断调度，恒走 Redis（异常时仍由 tryRedis 兜本地）
        if (!fallbackProperties.isEnabled()) {
            return tryRedis(key, windowSeconds, false, false);
        }
        // 步骤2：OPEN 态直接走本地兜底（冷却期内不碰 Redis，避免每请求都吃连接超时）
        CircuitBreaker.State state = circuitBreaker.getState();
        if (state == CircuitBreaker.State.OPEN) {
            return localIncrement(key, windowSeconds);
        }
        // 步骤3：申请调用许可（HALF_OPEN 下仅放行 1 个探测许可，其余请求拿不到许可）
        if (!circuitBreaker.tryAcquirePermission()) {
            return localIncrement(key, windowSeconds);
        }
        // 步骤4：走到这里说明已持有许可——HALF_OPEN 申请到的即是半开探测，其余为正常调用
        return tryRedis(key, windowSeconds, state == CircuitBreaker.State.HALF_OPEN, true);
    }

    /**
     * 执行一次 Redis Lua 固定窗口计数
     *
     * @param isProbe        true 表示 HALF_OPEN 态的探测请求（成功→CLOSED 恢复，失败→回 OPEN）
     * @param reportToBreaker 是否向熔断器上报成败；仅兜底总开关关闭（enabled=false）时传 false，
     *                        此时不申请许可也就不上报，避免向熔断器写入「无许可」的成功/失败样本
     * @return 当前窗口累计次数；脚本无返回（业务类，非故障）返回 0；Redis 异常则计入熔断并走本地兜底
     */
    private long tryRedis(String key, long windowSeconds, boolean isProbe, boolean reportToBreaker) {
        // 步骤0：快照上报前状态，用于判断本次调用是否真的触发了熔断状态切换（日志节流）
        CircuitBreaker.State before = reportToBreaker ? circuitBreaker.getState() : null;
        try {
            // 步骤1：执行 Lua 原子计数（INCR + 首次 EXPIRE）
            Long count = redisTemplate.execute(RATE_LIMIT_SCRIPT, Collections.singletonList(key), windowSeconds);
            // 步骤2：成功——上报熔断器（记录成功样本；半开探测成功则 HALF_OPEN → CLOSED）
            if (reportToBreaker) {
                circuitBreaker.onSuccess(0L, TimeUnit.MILLISECONDS);
                if (isProbe) {
                    log.info("Redis 熔断恢复: 探测成功，由本地兜底切回 Redis");
                }
            }
            // 步骤3：脚本无返回（业务类，非故障）→ 返回 0 兜底放行
            return count == null ? 0L : count;
        } catch (Exception e) {
            // 步骤4：异常——上报熔断器（CLOSED 下连续失败打开 / HALF_OPEN 探测失败回 OPEN），转本地兜底
            if (reportToBreaker) {
                circuitBreaker.onError(0L, TimeUnit.MILLISECONDS, e);
                if (circuitBreaker.getState() == CircuitBreaker.State.OPEN
                        && before != CircuitBreaker.State.OPEN) {
                    if (isProbe) {
                        log.warn("Redis 熔断探测失败，继续本地兜底降级: error={}", e.getMessage());
                    } else {
                        log.warn("Redis 连续故障，熔断打开（冷却 {}s），调用方转本地兜底: error={}",
                                fallbackProperties.getCooldownSeconds(), e.getMessage());
                    }
                }
            }
            return localIncrement(key, windowSeconds);
        }
    }

    // 熔断器的状态切换与指标打点由 CircuitBreaker 事件承担（见 guard/RedisGuardMetrics）

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
