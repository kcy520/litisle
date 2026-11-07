package com.litisle.commonredis.guard;

import com.litisle.commonredis.config.RateLimitFallbackProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * Redis 熔断守护（薄适配层，三态状态机由 Resilience4j CircuitBreaker 承载）
 *
 * 【为什么保留这个类】
 * 上游（LoginRetryLimiter）按 `acquire() / onSuccess() / onFailure()` 的方式调用，
 * 这里只做「调用方语义 → Resilience4j API」的翻译，签名保持不变、调用方零改动。
 * 状态机、滑动窗口、半开许可、冷却计时全部交给 CircuitBreaker，
 * 不再保留自研的 AtomicInteger 状态 + 连续失败计数 + openAtMillis 冷却判断。
 *
 * 【使用场景】
 * 1. 限流计数：RedisRateLimiter 直接注入 CircuitBreaker（同类自调用不走 AOP，禁止用 @CircuitBreaker 注解）
 * 2. 登录防爆破：LoginRetryLimiter 经本适配层调用（本类仅为兼容其既有签名而保留）
 *
 * 【三态语义（与自研版本保持一致）】
 * - CLOSED：Redis 正常，全量走 Redis
 * - OPEN：Redis 故障，冷却期内 acquire() 返回 LOCAL，不碰 Redis（避免每请求都吃连接超时）
 * - HALF_OPEN：冷却到期后自动进入，仅放行 1 个探测许可；探测成功 → CLOSED，探测失败 → 回 OPEN
 *
 * 【路径判定】
 * acquire() 的返回值含义：
 * - OPEN → LOCAL（熔断器拒绝调用，直接走本地兜底）
 * - HALF_OPEN 且拿到许可 → PROBE（本次是半开探测请求，最多 1 个）
 * - CLOSED 且拿到许可 → REDIS
 * - 其它（许可被拒）→ LOCAL
 *
 * 【必须注意的两条边界】
 * 1. `onSuccess(false)` 在 OPEN / HALF_OPEN 态必须直接忽略：否则会把熔断器强制打回 CLOSED，
 *    绕过半开探测（对应 LoginRetryLimiter#resetFailure 的复位路径）。
 * 2. 只有真正持有许可的调用才能上报成功/失败；`resetFailure` 这条「无许可复位」路径
 *    只在 CLOSED 下清空滑动窗口计数（circuitBreaker.reset()），不允许跨态复位。
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
public class RedisGuard {

    /**
     * 本次 Redis 操作应走的路径
     */
    public enum Path {
        /** 正常：走 Redis */
        REDIS,
        /** 半开探测：本次放行试 Redis（每轮冷却期仅一个许可） */
        PROBE,
        /** 降级：走调用方本地兜底，不碰 Redis */
        LOCAL
    }

    /**
     * 上报给熔断器时记录的单次调用时长（毫秒）
     *
     * 【为什么固定 0】本组件只关心「成功/失败」与滑动窗口失败率，
     * 不启用慢调用比例判定（slowCallRateThreshold 未配置），因此时长不参与任何计算，
     * 传 0 只是满足 Resilience4j API 的参数要求。
     */
    private static final long RECORDED_DURATION = 0L;

    /**
     * 熔断器（三态状态机 + 滑动窗口 + 半开许可 + 冷却计时全部由它实现）
     *
     * 由 CommonRedisAutoConfiguration 按显式等价配置创建：
     * failureRateThreshold=100、slidingWindowSize=3、minimumNumberOfCalls=3、
     * permittedNumberOfCallsInHalfOpenState=1、automaticTransitionFromOpenToHalfOpenEnabled=true。
     */
    private final CircuitBreaker circuitBreaker;

    /**
     * 降级配置（enabled / cooldownSeconds / instanceCount，yml: litisle.rate-limit.fallback）
     * 本类只读取 enabled：关闭兜底开关时恒走 Redis，由调用方 try-catch fail-safe。
     */
    private final RateLimitFallbackProperties properties;

    /**
     * 当前线程是否持有熔断器调用许可（acquire 拿到许可置 true，上报成败后清除）
     *
     * 【为什么需要】Resilience4j 要求「申请许可 → 上报成败」成对出现：
     * - 持有许可时才允许上报成功/失败（否则会向熔断器写入无许可样本）
     * - LoginRetryLimiter#resetFailure 属于「未申请许可的复位路径」，必须与正常上报区分开，
     *   否则会把 OPEN 直接打回 CLOSED，绕过半开探测
     * 使用 ThreadLocal 而非成员变量：同一线程内 acquire → 回调严格配对，线程池复用不会串号，
     * 上报后立即 remove 防止残留。
     */
    private final ThreadLocal<Boolean> permitHeld = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * 构造熔断守护
     *
     * @param circuitBreaker      Resilience4j 熔断器（三态状态机载体）
     * @param properties          降级配置（本类只用 enabled 开关）
     */
    public RedisGuard(CircuitBreaker circuitBreaker, RateLimitFallbackProperties properties) {
        this.circuitBreaker = circuitBreaker;
        this.properties = properties;
    }

    /**
     * 熔断调度：返回本次操作应走的路径（REDIS / PROBE / LOCAL）
     *
     * 【执行流程】
     * 1. 兜底总开关关闭 → 恒走 Redis（回退为调用方 try-catch fail-safe）
     * 2. OPEN 态 → 直接走本地（冷却期内不碰 Redis；冷却到期由熔断器自动转 HALF_OPEN）
     * 3. 申请一个调用许可（tryAcquirePermission）
     *    - 拒绝（HALF_OPEN 下许可已被占用）→ LOCAL
     *    - 允许 → 按申请瞬间的状态返回 PROBE（半开）或 REDIS（闭合）
     *
     * @return 路径决策（调用方据此分发 Redis 操作或本地兜底）
     */
    public Path acquire() {
        // 步骤1：兜底总开关关闭——跳过熔断调度，恒走 Redis
        if (!properties.isEnabled()) {
            return Path.REDIS;
        }
        // 步骤2：OPEN 态直接降级（自动转半开由 Resilience4j 的调度器负责，这里不手动 CAS）
        if (circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
            return Path.LOCAL;
        }
        // 步骤3：申请许可（HALF_OPEN 下最多放行 permittedNumberOfCallsInHalfOpenState=1 个）
        if (!circuitBreaker.tryAcquirePermission()) {
            return Path.LOCAL;
        }
        // 步骤4：许可到手——标记当前线程持证，回调上报时会据此判断是否成对
        permitHeld.set(Boolean.TRUE);
        // 步骤5：申请瞬间若处于 HALF_OPEN 即为半开探测，否则为正常闭合调用
        return circuitBreaker.getState() == CircuitBreaker.State.HALF_OPEN ? Path.PROBE : Path.REDIS;
    }

    /**
     * Redis 操作成功上报
     *
     * 【两条分支】
     * - 持证上报（acquire 成功后的正常/探测调用）：记录成功样本；
     *   探测成功会让 HALF_OPEN → CLOSED，并打恢复日志
     * - 无证上报（LoginRetryLimiter#resetFailure 的复位路径）：只在 CLOSED 下清空滑动窗口计数，
     *   OPEN / HALF_OPEN 一律忽略——绝不能借复位把熔断器打回 CLOSED（会绕过半开探测）
     *
     * @param isProbe true 表示 HALF_OPEN 态的探测请求成功
     */
    public void onSuccess(boolean isProbe) {
        boolean permitted = Boolean.TRUE.equals(permitHeld.get());
        // 上报一次即消费许可，无论成败都清理，避免线程池复用时状态串号
        permitHeld.remove();
        // 分支1：持证上报——正常记录成功（探测成功时 Resilience4j 内部完成 HALF_OPEN → CLOSED）
        if (permitted) {
            circuitBreaker.onSuccess(RECORDED_DURATION, TimeUnit.MILLISECONDS);
            if (isProbe) {
                log.info("Redis 熔断恢复: 探测成功，由本地兜底切回 Redis");
            }
            return;
        }
        // 分支2：无证复位——仅 CLOSED 下清空窗口计数；跨态复位会绕过半开探测，必须忽略
        CircuitBreaker.State current = circuitBreaker.getState();
        if (current == CircuitBreaker.State.CLOSED) {
            circuitBreaker.reset();
            return;
        }
        log.debug("Redis 熔断保护中（{}），忽略复位型成功上报，等待半开探测", current);
    }

    /**
     * Redis 操作失败上报
     *
     * 【语义映射】
     * - CLOSED 下连续 3 次失败（由显式窗口配置保证等价）→ OPEN
     * - HALF_OPEN 下探测失败 → 重新 OPEN，重新计冷却期
     * - 业务类异常（脚本返回 null 等）由调用方按「成功」上报，不走本方法，故不会被计为故障
     * - 无许可的失败上报（复位路径异常）不参与熔断统计：复位本身不是 Redis 健康信号，
     *   计入会让「重置失败」这种非关键路径干扰半开探测
     *
     * @param isProbe true 表示 HALF_OPEN 态的探测请求失败
     * @param e       触发失败的异常（仅取 message 入日志，不打堆栈防刷屏）
     */
    public void onFailure(boolean isProbe, Exception e) {
        boolean permitted = Boolean.TRUE.equals(permitHeld.get());
        // 上报一次即消费许可，无论成败都清理，避免线程池复用时状态串号
        permitHeld.remove();
        // 无证上报（resetFailure 异常路径）：不进入熔断统计，只留排障日志
        if (!permitted) {
            log.debug("Redis 熔断复位路径上报失败，不参与熔断统计: error={}", e.getMessage());
            return;
        }
        // 步骤1：快照上报前的状态，用于判断本次失败是否真的引发了状态切换
        CircuitBreaker.State before = circuitBreaker.getState();
        // 步骤2：上报失败（Resilience4j 内部按滑动窗口统计并决定是否打开熔断）
        circuitBreaker.onError(RECORDED_DURATION, TimeUnit.MILLISECONDS, e);
        // 步骤3：状态切换点在日志中体现（探测失败 warn / 闭合转打开 warn），天然节流防刷屏
        CircuitBreaker.State after = circuitBreaker.getState();
        if (after == CircuitBreaker.State.OPEN && before != CircuitBreaker.State.OPEN) {
            if (isProbe) {
                log.warn("Redis 熔断探测失败，继续本地兜底降级: error={}", e.getMessage());
            } else {
                log.warn("Redis 连续故障 {} 次，熔断打开（冷却 {}s），调用方转本地兜底: error={}",
                        circuitBreaker.getCircuitBreakerConfig().getSlidingWindowSize(),
                        properties.getCooldownSeconds(), e.getMessage());
            }
        }
    }
}
