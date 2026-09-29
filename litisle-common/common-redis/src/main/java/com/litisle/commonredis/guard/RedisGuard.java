package com.litisle.commonredis.guard;

import com.litisle.commonredis.config.RateLimitFallbackProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.boot.autoconfigure.cache.CacheProperties.Redis;

///RedisGuard 是一个「Redis 故障防护守卫」——它站在 Redis 操作的前面，
// 替调用方决定「这次请求走 Redis 还是走本地兜底」，并在 Redis 恢复时自动切回。名字里的 Guard = 守卫/防护。

/**
 * Redis 三态熔断守护（可复用组件，SSOT）
 *
 * 【为什么单独抽这个类】
 * 原先三态熔断状态机（CLOSED/OPEN/HALF_OPEN + 冷却 + 单线程探测 + 连续故障计数）
 * 内嵌于 RedisRateLimiter，属「单一事实来源」破坏点：RedisRateLimiter（限流计数）与
 * LoginRetryLimiter（登录防爆破）都需要同一套「Redis 故障感知 + 降级调度」逻辑，
 * 抽取后共用同一状态机，避免两处降级语义漂移。
 *
 * 【使用场景】
 * 1. 限流计数：RedisRateLimiter.increment 前调 acquire()，按返回路径分发
 * 2. 登录防爆破：LoginRetryLimiter 的 recordFailure/isLocked/resetFailure 同上
 * 3. 任意「Redis 辅助型操作 + 本地兜底实现」的组件：acquire() 决定路径，
 *    操作结束按成败回调 onSuccess/onFailure 上报状态机
 *
 * 【选型速查表】
 * | 场景                                     | 推荐做法                                     |
 * |------------------------------------------|----------------------------------------------|
 * | 限流计数类（有 LocalRateLimiter 兜底）   | RedisRateLimiter（内部已接入本组件）         |
 * | 安全闸口类（isLocked 等防护判断）        | 本组件 + 组件自有本地兜底（宁严勿松）        |
 * | 非关键读类（统计/缓存查询）              | 不接熔断，纯 try-catch fail-safe 即可        |
 * | 需要集群级熔断/控制台动态规则            | 不适用（本组件为进程内轻量兜底，两者互补）   |
 *
 * 【三态状态机】
 * - CLOSED：Redis 正常；连接/超时类失败连续达 FAILURE_THRESHOLD → OPEN
 *   （业务类异常如脚本返回 null 不算故障，不触发熔断）
 * - OPEN：冷却期内 acquire() 返回 LOCAL，不碰 Redis（避免每请求都吃连接超时）
 * - HALF_OPEN：冷却到期后仅一个线程 CAS 成功返回 PROBE，其余仍返回 LOCAL；
 *   探测成功 → CLOSED，探测失败 → 回 OPEN 重新计冷却
 *   （防冷却到期全量请求冲回 Redis 的惊群，也防一次侥幸成功就全量恢复）
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
 * 【典型用法】
 * <pre>
 *     Path path = redisGuard.acquire();
 *     if (path == Path.LOCAL) { return localFallback(...); }
 *     try {
 *         Object r = redisOps(...);
 *         redisGuard.onSuccess(path == Path.PROBE);
 *         return r;
 *     } catch (Exception e) {
 *         redisGuard.onFailure(path == Path.PROBE, e);
 *         return localFallback(...);
 *     }
 * </pre>
 *
 * 【日志】仅在状态切换点打（探测失败/熔断打开 warn、恢复 info），天然节流防刷屏；
 * 状态切换点也是 Micrometer 指标的天然埋点位置（将来接入，此处埋一次，使用方均受益）。
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
        /** 半开探测：本次放行试 Redis（每轮冷却期仅一个线程） */
        PROBE,
        /** 降级：走调用方本地兜底，不碰 Redis */
        LOCAL
    }

    /** CLOSED：Redis 正常，全量走 Redis */
    private static final int STATE_CLOSED = 0;
    /** OPEN：Redis 故障，全量走本地兜底，冷却期内不碰 Redis */
    private static final int STATE_OPEN = 1;
    /** HALF_OPEN：冷却到期，仅放单个探测请求试 Redis，其余继续本地 */
    private static final int STATE_HALF_OPEN = 2;

    /**
     * 熔断打开阈值：CLOSED 态下连接/超时类失败连续达此次数 → OPEN
     * （取小值快速切入降级，避免故障期每请求都吃连接超时拖垮接口延迟）
     */
    private static final int FAILURE_THRESHOLD = 3;

    /** 降级配置（enabled / cooldownSeconds / instanceCount，yml: litisle.rate-limit.fallback） */
    private final RateLimitFallbackProperties properties;

    /** 熔断状态（CLOSED/OPEN/HALF_OPEN）；AtomicInteger = 多线程安全读写，无需 synchronized */
    private final AtomicInteger state = new AtomicInteger(STATE_CLOSED);

    /** 连续故障计数（Redis 操作成功即清零） */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    /** 进入 OPEN 的时刻（毫秒），用于冷却期判断 */
    private volatile long openAtMillis;

    /** Micrometer 注册中心（可选注入，未接入 actuator 时为 null，指标静默停用不影响状态机） 
     * actuator:Spring Boot Actuator 是 Spring Boot 官方提供的「应用监控与管理模块」，用来暴露应用的内部运行状态给外部系统（如 Prometheus）采集。
    */
    private volatile MeterRegistry meterRegistry;
    // ① 埋点（已完成的代码）      RedisGuard 把状态写进 MeterRegistry → 指标数据在 JVM 内就绪  不引 actuator，自动告警是就失效了
    // ② 暴露（需 actuator）      actuator 提供 /actuator/prometheus 端点，把指标吐给外部
    // ③ 采集（需 Prometheus）    Prometheus 定时抓取端点，存储时间序列
    // ④ 告警（需 Alertmanager）  规则 redis_guard_degraded == 1 for 5m 命中 → 推钉钉/企微/短信


    /**
     * 当前是否处于降级态（OPEN/HALF_OPEN=1、CLOSED=0），暴露为 Gauge 供「降级超过 X 分钟」告警使用
     *
     * 【redis.guard.degraded = 1 的语义】
     * 表示「当前系统是否正处于 Redis 降级状态」，1 代表“是”，0 代表“否”。
     * 它不是在数“发生了几次降级”（那是 redis.guard.state.change Counter 的职责），
     * 而是在实时告诉监控系统：现在这一秒，系统是不是在降级中——
     * 所以告警规则用 redis_guard_degraded == 1 for 5m（持续为 1 达 5 分钟才报），
     * 而非按次数触发。
     */
    private final AtomicLong degraded = new AtomicLong();
    //当 Redis 故障触发熔断后，系统自动记录“当前是否处于降级状态”，Prometheus 持续抓取这个指标，一旦降级超过 5 分钟就自动告警，不再靠人盯着日志看。
    // 整体流程
    // Redis 故障
    // ↓
    // RedisGuard 熔断（CLOSED → OPEN）
    // ↓
    // 切本地兜底（静默降级）
    // ↓
    // 埋点：redis.guard.degraded = 1
    // ↓
    // Prometheus 定时抓取
    // ↓
    // 告警规则：redis_guard_degraded == 1 持续 5 分钟
    // ↓
    // 自动报警（钉钉 / 企微 / 短信 / PagerDuty）

//- `meterRegistry` 是 Micrometer 的注册中心（可理解为「指标的容器+导出器」）。声明为`volatile` + 可选注入 ：业务服务引了 actuator 才有这个 Bean，没引就是 null——此时指标静默停用， 状态机本身完全不受影响 （这是库模块不强依赖监控组件的关键设计）
// - `degraded` 是 Gauge 的数据载体。用`AtomicLong` 而不是普通变量，因为 Gauge 取值函数会在 Prometheus 抓取线程中并发读取，需要可见性保证


    public RedisGuard(RateLimitFallbackProperties properties) {
        this.properties = properties;
    }


//  当 Redis 故障触发熔断后，系统自动记录“当前是否处于降级状态”，
//  Prometheus 持续抓取这个指标，一旦降级超过 5 分钟就自动告警，不再靠人盯着日志看。
    /**
     * 可选注入 Micrometer 注册中心（由 CommonRedisAutoConfiguration 通过 ObjectProvider 注入）
     *
     * 【指标设计】（埋点位置即状态切换点，本层埋一次，RedisRateLimiter / LoginRetryLimiter 均受益）
     * - redis.guard.state.change（Counter，tags: from/to）：状态切换次数，观测故障频率与恢复速度
     * - redis.guard.degraded（Gauge，1=降级中/0=正常）：对应告警诉求「降级超过 X 分钟」，
     *   Prometheus 规则示例：redis_guard_degraded == 1 for 5m
     */
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        if (meterRegistry != null) {
            // Gauge 是「无状态探针」：注册时不记值，仅登记一个取值函数（degraded::doubleValue），
            // 每次 Prometheus 抓取时才回调读取当前值——传对象引用而非快照，degraded 变化 Gauge 自动反映
            Gauge.builder("redis.guard.degraded", degraded, AtomicLong::doubleValue)
                    .description("Redis 三态熔断是否处于降级态（OPEN/HALF_OPEN）")
                    .register(meterRegistry);
        }
    }

    /**
     * 状态切换埋点：Counter 按 from→to 维度记录切换次数，Gauge 同步降级标志；
     * meterRegistry 未注入时静默跳过（库模块不强依赖 micrometer 运行时）
     */
    private void recordStateChange(int from, int to) {
        // volatile 字段先快照到局部变量：保证 null 检查与后续使用基于同一引用（防御性写法）
        MeterRegistry registry = this.meterRegistry;
        if (registry == null) {
            return;
        }
        // Counter 按「名称 + tags 组合」幂等：同名同 tag 重复 register 返回同一实例；
        // 不同 from→to 组合 tag 不同 → 各自独立的计数序列（故障次数/恢复次数分开统计）
        Counter.builder("redis.guard.state.change")
                .tag("from", stateName(from))
                .tag("to", stateName(to))
                .description("Redis 三态熔断状态切换次数")
                .register(registry)
                .increment();
        // Gauge 取值同步：切到 CLOSED 归 0（正常），切到 OPEN/HALF_OPEN 置 1（降级中），
        // 即告警规则 redis_guard_degraded == 1 for 5m 的取值来源
        degraded.set(to == STATE_CLOSED ? 0L : 1L);
    }

    /** 状态常量 → 指标 tag 名 */
    private static String stateName(int state) {
        return switch (state) {
            case STATE_OPEN -> "OPEN";
            case STATE_HALF_OPEN -> "HALF_OPEN";
            default -> "CLOSED";
        };
    }

    /**
     * 熔断调度：返回本次操作应走的路径（REDIS/PROBE/LOCAL），纯读状态机 + 一次 CAS，无锁
     *
     * 【执行流程】
     * 1. 兜底总开关检查：关闭则恒走 Redis（回退为调用方 try-catch fail-safe）
     * 2. 读取熔断状态，按三态分发（volatile 读，无锁）
     * 3. OPEN 态：冷却期内走本地；到期后仅一个线程 CAS 成功者发探测
     * 4. HALF_OPEN 态：探测在途，其余请求继续本地
     * 5. CLOSED 态：正常走 Redis
     *
     * @return 路径决策（调用方据此分发 Redis 操作或本地兜底）
     */
    public Path acquire() {
        // 步骤1：兜底总开关检查——关闭则跳过熔断调度，恒走 Redis（故障时由调用方 try-catch fail-safe）
        if (!properties.isEnabled()) {
            return Path.REDIS;
        }
        // 步骤2：读取当前熔断状态，按三态分发（volatile 读，无锁）
        int current = state.get();
        // 步骤3：OPEN 态——Redis 已确认故障，分冷却期内/外两条路
        if (current == STATE_OPEN) {
            // 步骤3.1：冷却期内——直接走本地兜底，不碰 Redis（避免每请求都吃连接超时）
            if (System.currentTimeMillis() - openAtMillis < cooldownMillis()) {
                return Path.LOCAL;
            }
            // 步骤3.2：冷却到期——仅一个线程 CAS 进入 HALF_OPEN 发探测（防惊群），其余继续本地
            if (state.compareAndSet(STATE_OPEN, STATE_HALF_OPEN)) {
                return Path.PROBE;
            }
            return Path.LOCAL;
        }
        // 步骤4：HALF_OPEN 态——已有探测请求在途，其余继续本地
        if (current == STATE_HALF_OPEN) {
            return Path.LOCAL;
        }
        // 步骤5：CLOSED 态——正常走 Redis
        return Path.REDIS;
    }

    /**
     * Redis 操作成功：清零连续故障计数；探测成功则熔断恢复 CLOSED
     *
     * 【执行流程】
     * 1. 清零连续故障计数（任何一次成功都视为故障序列中断）
     * 2. 无条件回置 CLOSED（非探测态下是幂等写入，探测态下即恢复）
     * 3. 仅「探测成功且确有降级发生」时打恢复日志
     *
     * @param isProbe true 表示 HALF_OPEN 态的探测请求成功
     */
    public void onSuccess(boolean isProbe) {
        // 步骤1：清零连续故障计数（任何一次成功都视为故障序列中断）
        consecutiveFailures.set(0);
        // 无条件回置 CLOSED（非探测态下是幂等写入，探测态下即恢复）
        int prev = state.getAndSet(STATE_CLOSED);
        // 仅「探测成功且确有降级发生」时打恢复日志（状态切换点，天然节流防刷屏）
        if (isProbe && prev != STATE_CLOSED) {
            log.info("Redis 熔断恢复: 探测成功，由本地兜底切回 Redis");
        }
        // 状态切换埋点（仅确有降级发生时记一次恢复；CLOSED→CLOSED 幂等回置不计）
        if (prev != STATE_CLOSED) {
            recordStateChange(prev, STATE_CLOSED);
        }
    }

    /**
     * Redis 操作失败：探测失败回 OPEN 重新计冷却；CLOSED 态连续故障达阈值则熔断打开
     *
     * @param isProbe true 表示 HALF_OPEN 态的探测请求失败
     * @param e       触发失败的异常（仅取 message 入日志，不打堆栈防刷屏）
     */
    public void onFailure(boolean isProbe, Exception e) {
        // 探测失败——重置 OPEN 起点并回退状态，重新计冷却期（不允许直接恢复）
        if (isProbe) {
            openAtMillis = System.currentTimeMillis();
            state.set(STATE_OPEN);
            recordStateChange(STATE_HALF_OPEN, STATE_OPEN);
            log.warn("Redis 熔断探测失败，继续本地兜底降级: error={}", e.getMessage());
            return;
        }
        // CLOSED 态普通失败——连续故障计数 +1，达阈值则 CAS 打开熔断
        // （CAS 保证并发下只有最先到达阈值的那一个线程执行「记录时间 + 打日志」）
        if (consecutiveFailures.incrementAndGet() >= FAILURE_THRESHOLD
                && state.compareAndSet(STATE_CLOSED, STATE_OPEN)) {
            openAtMillis = System.currentTimeMillis();
            recordStateChange(STATE_CLOSED, STATE_OPEN);
            log.warn("Redis 连续故障 {} 次，熔断打开（冷却 {}s），调用方转本地兜底: error={}",
                    FAILURE_THRESHOLD, properties.getCooldownSeconds(), e.getMessage());
        }
    }

    /**
     * 冷却期时长（毫秒）：OPEN 态在冷却期内不碰 Redis，到期后放探测请求
     *
     * @return 冷却时长毫秒数（来自 litisle.rate-limit.fallback.cooldown-seconds 配置）
     */
    private long cooldownMillis() {
        return properties.getCooldownSeconds() * 1000L;
    }
}
