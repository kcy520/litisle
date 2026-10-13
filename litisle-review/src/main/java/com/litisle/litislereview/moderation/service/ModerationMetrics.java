package com.litisle.litislereview.moderation.service;

import com.litisle.litislereview.enums.PunishActionEnum;
import com.litisle.litislereview.enums.VerdictEnum;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 机审观测埋点组件（板块七 7-1，ObjectProvider optional 模式：未接 MeterRegistry 不影响核心功能，项目惯例对齐 {@link com.litisle.litislereview.dfa.WordlibMetrics}）
 *
 * <p>指标口径（§4.8）：</p>
 * <ul>
 *   <li>moderation.total（Prometheus 渲染 moderation_total{verdict=pass|reject|suspicious}）——机审三分态计数（3-1/3-2 判定落库处）</li>
 *   <li>moderation.cost（渲染 moderation_cost）——SPI 调用耗时 Timer（模型费/性能观测，缓存命中不计时）</li>
 *   <li>moderation.cache.hit（渲染 moderation_cache_hit{layer=result|blacklist}）——缓存命中计数（result=review 侧审核结果缓存；blacklist=file 侧拒传黑名单）</li>
 *   <li>moderation.punish（渲染 moderation_punish_total{action}）——处罚落库计数（5-1/5-4 punish_log 落库同源收口）</li>
 * </ul>
 *
 * <p>标签红线：仅允许 verdict/layer/action 限定枚举集合（枚举名小写派生，禁按词/内容 ID/用户打标签防基数爆炸）；
 * Counter/Timer 同名同标签懒注册自动幂等（Micrometer 惯例），无需手动维护单例。</p>
 */
@Component
public class ModerationMetrics {

    /** moderation_cache_hit layer 标签-审核结果缓存命中（review 侧 3-1/3-2 缓存查重复用结论时计数） */
    public static final String LAYER_RESULT = "result";

    /** 指标名常量（Prometheus 渲染为下划线：moderation_total / moderation_cost / moderation_cache_hit / moderation_punish_total） */
    private static final String METRIC_TOTAL = "moderation.total";
    private static final String METRIC_COST = "moderation.cost";
    private static final String METRIC_CACHE_HIT = "moderation.cache.hit";
    private static final String METRIC_PUNISH = "moderation.punish";

    /** MeterRegistry optional 提供器（未引 actuator 时 getIfAvailable() 返回 null，静默跳过埋点） */
    private final ObjectProvider<MeterRegistry> registryProvider;

    /**
     * 构造注入（ObjectProvider 惰性解析，非强制依赖）
     *
     * @param registryProvider MeterRegistry 提供器
     */
    public ModerationMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this.registryProvider = registryProvider;
    }

    /**
     * 机审三分态计数 +1（moderation_total{verdict}，判定落库处调用）
     *
     * @param verdict 机审判定（NONE 非判定态不计数；verdict 标签取枚举名小写：pass/reject/suspicious）
     */
    public void incrementTotal(VerdictEnum verdict) {
        if (verdict == null || verdict == VerdictEnum.NONE) {
            return;
        }
        // optional 模式：未接入 MeterRegistry（未引 actuator）时静默跳过，不影响核心功能
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        // Counter 按 verdict 懒注册复用（Micrometer 同名同标签自动幂等）
        Counter.builder(METRIC_TOTAL)
                .tag("verdict", verdict.name().toLowerCase())
                .register(registry)
                .increment();
    }

    /**
     * SPI 调用耗时记录（moderation_cost Timer，真实送审处调用；缓存命中/重投补发不计入）
     *
     * @param costMs 本次 SPI 调用耗时（毫秒，取自 ModerationResult.costMs 聚合口径）
     */
    public void recordCost(long costMs) {
        // optional 模式：未接入 MeterRegistry 时静默跳过
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        // Timer 懒注册复用（Micrometer 同名自动幂等），预量测口径记录
        registry.timer(METRIC_COST).record(Math.max(costMs, 0L), TimeUnit.MILLISECONDS);
    }

    /**
     * 缓存命中计数 +1（moderation_cache_hit{layer}）
     *
     * @param layer 命中层（仅 result=审核结果缓存 / blacklist=拒传黑名单，禁扩展其他层防基数爆炸）
     */
    public void incrementCacheHit(String layer) {
        // optional 模式：未接入 MeterRegistry 时静默跳过
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        Counter.builder(METRIC_CACHE_HIT)
                .tag("layer", layer)
                .register(registry)
                .increment();
    }

    /**
     * 处罚落库计数 +1（moderation_punish_total{action}，punish_log 落库同源收口处调用）
     *
     * @param action 处罚动作（action 标签取枚举名小写：mute/ban/content_takedown/warning）
     */
    public void incrementPunish(PunishActionEnum action) {
        if (action == null) {
            return;
        }
        // optional 模式：未接入 MeterRegistry 时静默跳过
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        Counter.builder(METRIC_PUNISH)
                .tag("action", action.name().toLowerCase())
                .register(registry)
                .increment();
    }
}
