package com.litisle.commonredis.guard;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Redis 熔断指标桥接（把 Resilience4j 熔断状态映射回原有指标名）
 *
 * 【为什么需要这个类】
 * 自研 RedisGuard 暴露的两组指标已被 Prometheus 告警规则直接引用：
 * - `redis.guard.degraded`（Gauge，1=降级中）→ 告警 RedisGuardDegraded 的 `expr: redis_guard_degraded == 1`
 * - `redis.guard.state.change`（Counter，tag from/to）→ 观测切换次数
 * 指标一旦断供，Prometheus 表现为 **no data 而不是报错**，告警会静默消失。
 * 因此这里沿用**同名指标**桥接，`docker/init/prometheus/alert-rules.yml` 不需要任何改动。
 *
 * 【取值来源】
 * - Gauge：每次抓取时读取 `CircuitBreaker#getState()`，非 CLOSED 即 1（降级中）
 * - Counter：监听熔断器状态切换事件，按 from→to 打点（与自研版本 tag 口径一致）
 *
 * 【可选依赖】
 * MeterRegistry 由调用方（CommonRedisAutoConfiguration）通过 ObjectProvider 可选传入；
 * 未引入 actuator 的服务传 null 时本类不做任何注册，熔断状态机不受影响。
 *
 * @author litisle
 */
public final class RedisGuardMetrics {

    /** 降级态 Gauge 指标名（沿用自研版本名称，告警规则零改动） */
    public static final String DEGRADED_METRIC_NAME = "redis.guard.degraded";

    /** 状态切换 Counter 指标名（沿用自研版本名称） */
    public static final String STATE_CHANGE_METRIC_NAME = "redis.guard.state.change";

    /**
     * 工具类禁止实例化
     */
    private RedisGuardMetrics() {
    }

    /**
     * 注册熔断指标（Gauge + 状态切换 Counter）
     *
     * @param circuitBreaker 被观测的熔断器
     * @param meterRegistry  Micrometer 注册中心；为 null 时静默跳过（未引入 actuator 的服务）
     */
    public static void register(CircuitBreaker circuitBreaker, MeterRegistry meterRegistry) {
        // 可选依赖未就绪：不注册任何指标，避免库模块强依赖监控组件
        if (circuitBreaker == null || meterRegistry == null) {
            return;
        }
        // Gauge 是「无状态探针」：注册时只登记取值函数，每次抓取才回调读取当前状态，
        // 因此熔断状态变化后无需重新注册，Prometheus 下次抓取即可看到新值
        Gauge.builder(DEGRADED_METRIC_NAME, circuitBreaker, RedisGuardMetrics::degradedValue)
                .description("Redis 三态熔断是否处于降级态（OPEN/HALF_OPEN）")
                .register(meterRegistry);
        // Counter 依赖状态切换事件：Resilience4j 在每次状态迁移时回调，
        // 与自研版本「切换点埋一次」的口径一致（CLOSED→CLOSED 不存在切换，故不会重复计数）
        circuitBreaker.getEventPublisher().onStateTransition(event -> Counter
                .builder(STATE_CHANGE_METRIC_NAME)
                .tag("from", event.getStateTransition().getFromState().name())
                .tag("to", event.getStateTransition().getToState().name())
                .description("Redis 三态熔断状态切换次数")
                .register(meterRegistry)
                .increment());
    }

    /**
     * 计算降级 Gauge 取值
     *
     * @param circuitBreaker 熔断器
     * @return 1=降级中（OPEN / HALF_OPEN），0=正常（CLOSED）
     */
    private static double degradedValue(CircuitBreaker circuitBreaker) {
        return circuitBreaker.getState() == CircuitBreaker.State.CLOSED ? 0D : 1D;
    }
}
