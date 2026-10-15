package com.litisle.litislereview.dfa;

import com.litisle.litislereview.enums.WordSceneEnum;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 词库观测埋点（ObjectProvider optional 模式：未接 MeterRegistry 不影响核心功能，项目惯例）
 *
 * 它的用途是 词库灰度治理 ：一个词从灰度（MID）转正（HIGH）前，必须观察它的误报率——运营在误报上报接口点击上报，
 * 每次上报计数 +1，Prometheus 抓取后可以算出「该场景误报上报量 / 拦截量」，
 * 低于配置阈值（`gray-misreport-rate` ）才允许 MID→HIGH 转正。这是 灰度词转正的唯一量化数据源 ，没有这个计数器，灰度转正就只能拍脑袋决策。
 * 这也是为什么说 auth 不需要它——误报上报流程只存在于 review（词库的管理方），auth 只是词库的只读消费方，没有上报动作可计数。
 *
 * <p>指标口径（§4.8）：</p>
 * <p>wordlib.misreport（Prometheus 渲染 wordlib_misreport_total{scene}）——误报上报计数，
 * 灰度转正唯一数据源（误报率低于 gray-misreport-rate 才可 MID→HIGH）；标签仅 scene，
 * 禁按 word 打标签防基数爆炸</p>
 */
@Component
public class WordlibMetrics {

    /** wordlib_misreport 指标名（Prometheus 渲染 wordlib_misreport_total） */
    private static final String METRIC_MISREPORT = "wordlib.misreport";

    /** 误报计数的 scene 标签 key */
    private static final String TAG_SCENE = "scene";

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
        Counter.builder(METRIC_MISREPORT)
                .tag(TAG_SCENE, scene.getCode())
                .register(registry)
                .increment();
    }
}