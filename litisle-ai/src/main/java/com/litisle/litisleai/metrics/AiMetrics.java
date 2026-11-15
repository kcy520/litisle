package com.litisle.litisleai.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 侧指标统一打点（1.5：指标名集中定义，避免同一语义多个指标名）
 *
 * <p>【指标清单】ai_call_total{purpose,provider,result} / ai_call_latency{purpose}
 * （Timer，P50/P95/P99）/ ai_tokens_total{purpose,direction} / ai_quota_exceeded_total{purpose} /
 * ai_generation_total{scene,result} / ai_guard_state{provider}；代码侧点号、Prometheus 渲染为下划线。</p>
 *
 * <p>【口径】按用户用量不加指标标签（防高基数），用户维度用量统一由 ai_usage_daily 承担。</p>
 *
 * <p>【降级语义】MeterRegistry optional 模式（对齐 review 的 ObjectProvider 惯例）：
 * 未接 actuator 时所有打点静默跳过，不影响核心功能。</p>
 */
@Slf4j
@Component
public class AiMetrics {

    /** 模型调用总次数（结果计数） */
    private static final String METRIC_CALL_TOTAL = "ai.call.total";

    /** 模型调用耗时（Timer，分 purpose） */
    private static final String METRIC_CALL_LATENCY = "ai.call.latency";

    /** token 用量计数（direction=in/out） */
    private static final String METRIC_TOKENS_TOTAL = "ai.tokens.total";

    /** 配额/预算拒绝计数 */
    private static final String METRIC_QUOTA_EXCEEDED = "ai.quota.exceeded.total";

    /** 生成动作计数（板块二生成端点接入后消费） */
    private static final String METRIC_GENERATION_TOTAL = "ai.generation.total";

    /** 通道健康状态 Gauge（1=正常 0=异常） */
    private static final String METRIC_GUARD_STATE = "ai.guard.state";

    /** Micrometer 注册中心提供器（optional：未接 actuator 静默跳过） */
    private final ObjectProvider<MeterRegistry> registryProvider;

    /**
     * 构造注入注册中心提供器
     *
     * @param registryProvider MeterRegistry 提供器
     */
    public AiMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this.registryProvider = registryProvider;
    }

    /**
     * 记录一次模型调用结果计数（ai_call_total{purpose,provider,result}）
     *
     * @param purpose  用途编码
     * @param provider 通道提供方标识
     * @param result   调用结果（success/fail）
     */
    public void recordCall(String purpose, String provider, String result) {
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        Counter.builder(METRIC_CALL_TOTAL)
                .tag("purpose", purpose)
                .tag("provider", provider)
                .tag("result", result)
                .register(registry)
                .increment();
    }

    /**
     * 记录一次模型调用耗时（ai_call_latency{purpose}，Timer 支持分位数统计）
     *
     * @param purpose       用途编码
     * @param durationMs    耗时（毫秒）
     */
    public void recordLatency(String purpose, long durationMs) {
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        Timer.builder(METRIC_CALL_LATENCY)
                .tag("purpose", purpose)
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录 token 用量（ai_tokens_total{purpose,direction}，按值累加）
     *
     * @param purpose   用途编码
     * @param direction 方向（in/out）
     * @param tokens    token 数（null 按 0 忽略）
     */
    public void recordTokens(String purpose, String direction, Integer tokens) {
        if (tokens == null || tokens <= 0) {
            return;
        }
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        Counter.builder(METRIC_TOKENS_TOTAL)
                .tag("purpose", purpose)
                .tag("direction", direction)
                .register(registry)
                .increment(tokens);
    }

    /**
     * 记录一次配额/预算拒绝（ai_quota_exceeded_total{purpose}）
     *
     * @param purpose 用途编码
     */
    public void recordQuotaExceeded(String purpose) {
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        Counter.builder(METRIC_QUOTA_EXCEEDED)
                .tag("purpose", purpose)
                .register(registry)
                .increment();
    }

    /**
     * 记录一次生成动作（ai_generation_total{scene,result}；板块二生成端点接线后消费）
     *
     * @param scene  业务场景
     * @param result 动作结果（success/fail）
     */
    public void recordGeneration(String scene, String result) {
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        Counter.builder(METRIC_GENERATION_TOTAL)
                .tag("scene", scene)
                .tag("result", result)
                .register(registry)
                .increment();
    }

    /**
     * 注册通道健康 Gauge（ai_guard_state{provider}：1=正常 0=异常）
     *
     * @param provider 通道提供方标识
     * @param healthy  健康标记（Gauge 每次抓取实时读取）
     */
    public void registerGuardGauge(String provider, AtomicBoolean healthy) {
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        Gauge.builder(METRIC_GUARD_STATE, healthy, state -> state.get() ? 1 : 0)
                .tag("provider", provider)
                .register(registry);
    }
}
