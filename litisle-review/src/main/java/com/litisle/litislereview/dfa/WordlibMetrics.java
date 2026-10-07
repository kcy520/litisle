package com.litisle.litislereview.dfa;

import com.litisle.litislereview.enums.WordSceneEnum;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 词库观测埋点（ObjectProvider optional 模式：未接 MeterRegistry 不影响核心功能，项目惯例）
 *
 * <p>指标口径（§4.8）：</p>
 * <p>wordlib.misreport（Prometheus 渲染 wordlib_misreport_total{scene}）——误报上报计数，
 * 灰度转正唯一数据源（误报率低于 gray-misreport-rate 才可 MID→HIGH）；标签仅 scene，
 * 禁按 word 打标签防基数爆炸</p>
 */
@Component
public class WordlibMetrics {

    private final ObjectProvider<MeterRegistry> registryProvider;

    public WordlibMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this.registryProvider = registryProvider;
    }

    /**
     * 误报上报计数 +1（scene 标签）
     */
    public void incrementMisreport(WordSceneEnum scene) {
        // optional 模式：未接入 MeterRegistry（未引 actuator）时静默跳过，不影响核心功能
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        // Counter 按 scene 懒注册复用（Micrometer 同名同标签自动幂等）；禁按 word 加标签防基数爆炸
        Counter.builder("wordlib.misreport")
                .tag("scene", scene.getCode())
                .register(registry)
                .increment();
    }
}