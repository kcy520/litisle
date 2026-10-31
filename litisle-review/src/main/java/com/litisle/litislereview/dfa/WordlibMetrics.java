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
 * <p>wordlib.rebuild（Prometheus 渲染 wordlib_rebuild_total{scene,result}）——热更构建
 * 成功/失败计数（批次2 ④）：失败率突增=词库/存储异常（旧引擎兜底但实例词库停更）</p>
 */
@Component
public class WordlibMetrics {

    /** wordlib.misreport 指标名（Prometheus 渲染 wordlib_misreport_total） */
    private static final String METRIC_MISREPORT = "wordlib.misreport";

    /** 词库热更构建指标名（Prometheus 渲染 wordlib_rebuild_total，批次2 ④） */
    private static final String METRIC_REBUILD = "wordlib.rebuild";

    /** 误报计数/热更构建计数的 scene 标签 key */
    private static final String TAG_SCENE = "scene";

    /** 热更构建结果标签 key（success/fail） */
    private static final String TAG_RESULT = "result";

    private final ObjectProvider<MeterRegistry> registryProvider;

    public WordlibMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this.registryProvider = registryProvider;
    }

// 新词入库 → MID 试用期（只标记不拦截，用户无感知，无误报可言）
//               ↓ 观察期没出问题，运营手动转正
//            HIGH（开始同步拦截，词获得"杀权"）
//               ↓ 这时候才可能杀错人 ←—— 误报发生在这里
//            被拦的作者喊冤（误报上报）→ wordlib_misreport_total +1
//               ↓ 误报过多
//            运营降级 HIGH → MID（收回杀权，回到观察期）或删词

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

    /**
     * 词库热更构建计数（批次2 ④：result=success/fail + scene 标签）
     *
     * <p>失败率突增说明词库/存储异常（旧引擎仍在兜底，但实例词库已停更），
     * 配合 Alertmanager 做「热更失败」告警（阈值外置 §五）</p>
     *
     * @param scene   词库场景 code
     * @param success 构建是否成功
     */
    public void incrementRebuild(String scene, boolean success) {
        // optional 模式：未接 MeterRegistry 静默跳过
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        Counter.builder(METRIC_REBUILD)
                .tag(TAG_SCENE, scene)
                .tag(TAG_RESULT, success ? "success" : "fail")
                .register(registry)
                .increment();
    }
}