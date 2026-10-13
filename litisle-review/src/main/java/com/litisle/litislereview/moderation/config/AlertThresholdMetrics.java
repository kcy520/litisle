package com.litisle.litislereview.moderation.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 告警阈值暴露组件（板块七 7-3：Alertmanager 六规则「阈值外置」的落地方，SSOT 收口）
 *
 * <p>【为什么需要本类】Prometheus 告警规则文件（alert-rules.yml）是静态 yml，无法直读 Nacos。
 * 若把阈值写死在规则表达式中，运营调阈就要改规则文件并触发 Prometheus 重载——违背「阈值外置
 * §4.7 alert.*」的清单要求。本类把阈值以 Gauge 指标形式暴露给 Prometheus，规则表达式改为
 * 引用阈值指标（moderation_alert_threshold{rule=...}）而非写死数字：改 Nacos 阈值 → 服务重绑
 * 属性 → 抓取端数值更新 → 告警判定即时生效，全程零规则文件变更（Prometheus 社区标准做法）。</p>
 *
 * <p>【指标口径】moderation_alert_threshold{rule=...}，rule 标签仅六个固定枚举值（防基数爆炸）：</p>
 * <ul>
 *   <li>suspicious-backlog     —— 存疑积压阈值（①，默认 50）</li>
 *   <li>claim-timeout-minutes  —— 在办超时阈值分钟数（②，默认 30，复用 sweeper.claimTimeoutMinutes）</li>
 *   <li>dlq-backlog            —— DLQ 堆积阈值（③，默认 100）</li>
 *   <li>moderation-fail-rate   —— 机审失败率阈值（④，默认 0.2）</li>
 *   <li>pass-rate-drop         —— 通过率相对基线跌幅阈值（⑤，默认 0.3）</li>
 *   <li>cache-hit-drop         —— 缓存命中率跌落阈值（⑥，默认 0.5）</li>
 * </ul>
 *
 * <p>【为什么②也要暴露】②的表达式（increase(moderation_claim_timeout_repool_total[15m]) > 0）
 * 不直接比较该指标，但 Grafana 面板与告警 annotations 需要展示当前阈值口径，
 * 暴露后运营可在看板上确认「现在生效的超时判定线是多少分钟」。</p>
 *
 * <p>【初始化时机】@PostConstruct 惰性注册（未接 actuator 时 registry 为 null 静默跳过，
 * 对齐 WordlibMetrics/ReviewTaskSweeper 的 ObjectProvider optional 惯例）。Gauge 绑定
 * {@link ModerationProperties} getter——配置对象属性值随 Nacos 重绑自动更新，Gauge 抓取即时反映。</p>
 *
 * @author litisle
 */
@Component
public class AlertThresholdMetrics {

    /** 阈值指标名（Prometheus 渲染为 moderation_alert_threshold） */
    private static final String METRIC_THRESHOLD = "moderation.alert.threshold";

    /** rule 标签-存疑积压阈值（规则①） */
    private static final String RULE_SUSPICIOUS_BACKLOG = "suspicious-backlog";

    /** rule 标签-在办超时阈值分钟数（规则②） */
    private static final String RULE_CLAIM_TIMEOUT = "claim-timeout-minutes";

    /** rule 标签-DLQ 堆积阈值（规则③） */
    private static final String RULE_DLQ_BACKLOG = "dlq-backlog";

    /** rule 标签-机审失败率阈值（规则④） */
    private static final String RULE_MODERATION_FAIL_RATE = "moderation-fail-rate";

    /** rule 标签-通过率相对基线跌幅阈值（规则⑤） */
    private static final String RULE_PASS_RATE_DROP = "pass-rate-drop";

    /** rule 标签-缓存命中率跌落阈值（规则⑥） */
    private static final String RULE_CACHE_HIT_DROP = "cache-hit-drop";

    /** 机审域可调参数（alert.* 五项 + sweeper.claim-timeout-minutes 阈值源） */
    private final ModerationProperties properties;

    /** Micrometer 注册中心（optional 模式：未接 actuator 静默跳过，对齐 WordlibMetrics 惯例） */
    private final ObjectProvider<MeterRegistry> registryProvider;

    /**
     * 构造注入阈值源与注册中心提供器
     *
     * @param properties        机审域可调参数（告警阈值所在）
     * @param registryProvider  MeterRegistry 提供器
     */
    public AlertThresholdMetrics(ModerationProperties properties,
                                 ObjectProvider<MeterRegistry> registryProvider) {
        this.properties = properties;
        this.registryProvider = registryProvider;
    }

    /**
     * 启动期惰性注册六个阈值 Gauge（同名不同标签，Micrometer 惰性注册自动幂等）
     *
     * <p>绑定 Lambda 直读 properties getter：Nacos 阈值变更重绑后，下一次抓取即取到新值，
     * 告警判定零重载生效——这就是「阈值外置」的运行时语义。</p>
     */
    @PostConstruct
    public void registerThresholdGauges() {
        // optional 模式：未接入 MeterRegistry（未引 actuator）时静默跳过，不影响核心功能
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        // ①存疑积压阈值 + ③DLQ 堆积阈值 + ④机审失败率 + ⑤通过率跌幅 + ⑥缓存命中跌幅（alert.* 五项）
        Gauge.builder(METRIC_THRESHOLD, properties,
                        p -> p.getAlert().getSuspiciousBacklogThreshold())
                .tag("rule", RULE_SUSPICIOUS_BACKLOG).register(registry);
        Gauge.builder(METRIC_THRESHOLD, properties,
                        p -> p.getAlert().getDlqBacklogThreshold())
                .tag("rule", RULE_DLQ_BACKLOG).register(registry);
        Gauge.builder(METRIC_THRESHOLD, properties,
                        p -> p.getAlert().getModerationFailRate())
                .tag("rule", RULE_MODERATION_FAIL_RATE).register(registry);
        Gauge.builder(METRIC_THRESHOLD, properties,
                        p -> p.getAlert().getPassRateDropRatio())
                .tag("rule", RULE_PASS_RATE_DROP).register(registry);
        Gauge.builder(METRIC_THRESHOLD, properties,
                        p -> p.getAlert().getCacheHitDropRatio())
                .tag("rule", RULE_CACHE_HIT_DROP).register(registry);
        // ②在办超时阈值（分钟）：复用 sweeper.claimTimeoutMinutes，不另立配置防口径漂移
        Gauge.builder(METRIC_THRESHOLD, properties,
                        p -> p.getSweeper().getClaimTimeoutMinutes())
                .tag("rule", RULE_CLAIM_TIMEOUT).register(registry);
    }
}
