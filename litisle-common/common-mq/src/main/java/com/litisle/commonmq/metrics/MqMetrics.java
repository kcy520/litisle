package com.litisle.commonmq.metrics;


import io.micrometer.core.instrument.MeterRegistry;

/**
 * MQ 收发指标埋点（common-mq 预置工具，按需使用；观测语义对齐 common-redis 的 RedisGuardMetrics：
 * 指标注册 + MeterRegistry 缺失时静默空转）。
 *
 * 【指标】
 * - mq_send_total{exchange, result}     result ∈ success | failure
 * - mq_consume_total{queue, result}     result ∈ success | biz_drop | unexpected
 *   （三分支与 MqAckHelper ACK 纪律一一对齐：成功 ack / 业务性失败 ack 丢弃 / 非预期 nack 入 DLQ）
 *
 * 【降级语义】MeterRegistry 为 null（消费方未接 Micrometer）时全方法静默空转，
 * 不埋点不报错——观测是增强件，故障/缺失不得阻断消费主流程。
 *
 * 【tag 基数纪律】exchange/queue 均为 MqConstant 常量（有限枚举值），禁拼动态串，
 * 防 Prometheus 时间线基数爆炸（对齐 wordlib_misreport_total 仅限 scene 标签的既定纪律）。
 *
 * 【触发条件】review 开发时接入告警面板：消费侧接 recordConsume（MqAckHelper 调用点
 * 旁路埋点），发送侧接 recordSend（MqSendUtil 两档语义各记一次）。
 *
 * 【使用示例】
 * <pre>
 *     private final MqMetrics mqMetrics; // 可为 null（未接 Micrometer），判空后调用
 *
 *     mqMetrics.recordSend(exchange, true);   // 发送成功
 *     mqMetrics.recordConsume(queue, MqMetrics.ConsumeResult.BIZ_DROP); // 业务性失败丢弃
 * </pre>
 */
public class MqMetrics {

    /** 发送总量指标名（Prometheus 侧自动转 mq_send_total_total，Grafana 配置注意） */
    public static final String METRIC_SEND = "mq_send_total";

    /** 消费总量指标名 */
    public static final String METRIC_CONSUME = "mq_consume_total";

    private static final String TAG_EXCHANGE = "exchange";
    private static final String TAG_QUEUE = "queue";
    private static final String TAG_RESULT = "result";

    /** 可为 null：未接 Micrometer 的消费方由自动配置注入 null，全方法静默空转 */
    private final MeterRegistry registry;

    public MqMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 消费结果枚举（与 MqAckHelper ACK 纪律三分支对齐，SSOT 靠 Javadoc 锁口径） */
    public enum ConsumeResult {
        /** 业务成功 ack */
        SUCCESS("success"),
        /** 业务性失败（BizException）ack 丢弃 */
        BIZ_DROP("biz_drop"),
        /** 非预期异常 nack 入 DLQ */
        UNEXPECTED("unexpected");

        private final String tag;

        ConsumeResult(String tag) {
            this.tag = tag;
        }

        public String getTag() {
            return tag;
        }
    }

    /**
     * 记录一次 MQ 发送结果
     *
     * @param exchange 交换机名（MqConstant 常量，禁动态串）
     * @param success  是否发送成功
     */
    public void recordSend(String exchange, boolean success) {
        if (registry == null) {
            return;
        }
        registry.counter(METRIC_SEND, TAG_EXCHANGE, exchange, TAG_RESULT, success ? "success" : "failure")
                .increment();
    }

    /**
     * 记录一次 MQ 消费结果
     *
     * @param queue  队列名（MqConstant 常量，禁动态串）
     * @param result 消费结果三分支
     */
    public void recordConsume(String queue, ConsumeResult result) {
        if (registry == null) {
            return;
        }
        registry.counter(METRIC_CONSUME, TAG_QUEUE, queue, TAG_RESULT, result.getTag()).increment();
    }
}