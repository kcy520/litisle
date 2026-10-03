package com.litisle.commonmq.consume;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * MQ 幂等消费辅助（common-mq 预置工具，按需使用；防 MQ 至少一次投递语义导致的重复消费）。
 *
 * 【机制】SETNX 去重键 + 外置 TTL：首次消费 SETNX 成功返回 true（放行处理），
 * 重复消息 SETNX 失败返回 false（调用方 ack 丢弃）。
 *
 * 【key 派生纪律】key = 固定前缀 + queue + eventId，两段均为服务端值
 * （queue 来自 MqConstant 常量、eventId 必须由服务端 UUID 生成），符合
 * 「Redis key 禁拼用户原串」纪律。⚠️ 调用方严禁把用户可控原串塞进 eventId。
 *
 * 【降级选型速查表】（Redis 不可用/未接入时，宁严勿松由调用方选择策略）
 * ┌──────────────────┬──────────────────┬─────────────────────────────────────────┐
 * │ 调用方法          │ 降级时返回        │ 适用场景                                  │
 * ├──────────────────┼──────────────────┼─────────────────────────────────────────┤
 * │ tryAcquire       │ true（放行）      │ 重复消费代价低/业务侧有天然幂等兜底           │
 * │ tryAcquireStrict │ false（当重复丢弃）│ 非幂等副作用（违规分累加/配额扣减），宁漏勿重 │
 * └──────────────────┴──────────────────┴─────────────────────────────────────────┘
 * strict 模式降级导致的消息丢弃会留 error 级日志，可凭日志人工补偿；
 * 若业务既不容忍重复也不容忍丢失，请改用业务层强幂等（如 DB 唯一索引/CAS）。
 *
 * 【触发条件（何时该接入本类）】消费者存在非幂等副作用——计数累加、配额扣减、
 * 状态不可逆流转。file 侧 updateSafeFlag 为目标态短路写、天然幂等，无需接入。
 *
 * 【使用示例】（与 MqTraceSupport / MqAckHelper 组合的标准消费模板）
 * <pre>
 *     &#64;RabbitListener(queues = MqConstant.QUEUE_XXX)
 *     public void onXxx(XxxEvent event, Message message, Channel channel) throws IOException {
 *         MqTraceSupport.restore(message);
 *         try {
 *             if (!mqIdempotentHelper.tryAcquire("litisle.xxx.queue", event.getEventId())) {
 *                 return; // 重复消息，直接走 finally 的 ack 丢弃
 *             }
 *             MqAckHelper.executeWithAck("litisle.xxx.queue", message, channel,
 *                     () -> xxxService.handle(event));
 *         } finally {
 *             MqTraceSupport.clear();
 *         }
 *     }
 * </pre>
 */
@Slf4j
public class MqIdempotentHelper {

    /** 去重 key 前缀（固定常量，与限流等其他 Redis key 空间隔离） */
    private static final String KEY_PREFIX = "litisle:mq:idempotent:";

    /** 可为 null：消费方未接 Redis 时由自动配置注入 null，全路径降级（见选型速查表） */
    private final StringRedisTemplate redisTemplate;

    /** 去重键存活时长（秒），外置配置 litisle.mq.idempotent.dedup-ttl-seconds */
    private final long dedupTtlSeconds;

    public MqIdempotentHelper(StringRedisTemplate redisTemplate, long dedupTtlSeconds) {
        this.redisTemplate = redisTemplate;
        this.dedupTtlSeconds = dedupTtlSeconds;
    }

    /**
     * 幂等尝试（宽容档）：Redis 不可用/未接入时降级放行（返回 true），由业务侧幂等兜底。
     *
     * @param queue   队列名（MqConstant 常量，key 派生 + 日志定位用）
     * @param eventId 事件唯一 ID（服务端 UUID，禁用用户原串）
     * @return true=首次消费，放行处理；false=重复消息，调用方应 ack 丢弃
     */
    public boolean tryAcquire(String queue, String eventId) {
        return tryAcquire(queue, eventId, true);
    }

    /**
     * 幂等尝试（严格档）：Redis 不可用/未接入时降级拦截（返回 false，宁严勿松）。
     * 适用于非幂等副作用消费者（违规分累加/配额扣减）——宁可丢消息留日志人工补偿，
     * 不可重复执行副作用。
     *
     * @param queue   队列名（MqConstant 常量，key 派生 + 日志定位用）
     * @param eventId 事件唯一 ID（服务端 UUID，禁用用户原串）
     * @return true=首次消费，放行处理；false=重复消息或降级拦截，调用方应 ack 丢弃
     */
    public boolean tryAcquireStrict(String queue, String eventId) {
        return tryAcquire(queue, eventId, false);
    }

    /**
     * 主体逻辑（两档共用）：SETNX + TTL 原子占位；异常/未接 Redis 按降级策略返回。
     */
    private boolean tryAcquire(String queue, String eventId, boolean allowOnDegraded) {
        // 步骤1：参数防御——eventId/queue 缺失说明调用方接线错误，无法构造去重键，
        // 按降级策略返回并 warn 留痕（strict 档宁严勿松，宽容档交业务兜底）
        if (queue == null || queue.isBlank() || eventId == null || eventId.isBlank()) {
            log.warn("MQ 幂等去重参数缺失，跳过 SETNX 按降级策略返回（allowOnDegraded={}），queue={}，eventId={}",
                    allowOnDegraded, queue, eventId);
            return allowOnDegraded;
        }
        // 步骤2：未接 Redis（StringRedisTemplate 缺失）→ 降级放行/拦截 + warn
        if (redisTemplate == null) {
            log.warn("Redis 未接入，MQ 幂等去重降级（allowOnDegraded={}），需业务侧幂等兜底，queue={}，eventId={}",
                    allowOnDegraded, queue, eventId);
            return allowOnDegraded;
        }
        // 步骤3：服务端派生去重键（前缀常量 + queue 常量 + 服务端 UUID，无用户原串）
        String key = KEY_PREFIX + queue + ":" + eventId;
        try {
            // 步骤4：SETNX + TTL 原子操作——true=首次占位成功（放行），false=已存在（重复消息）
            // value 固定写 "1"：本场景只做存在性判断，无需存业务数据（对齐「缓存 value 一律 JSON」
            // 纪律不冲突，"1" 为标记值非业务数据）
            Boolean first = redisTemplate.opsForValue()
                    .setIfAbsent(key, "1", Duration.ofSeconds(dedupTtlSeconds));
            boolean acquired = Boolean.TRUE.equals(first);
            if (!acquired) {
                log.warn("MQ 重复消息拦截（幂等命中），queue={}，eventId={}", queue, eventId);
            }
            return acquired;
        } catch (Exception e) {
            // 步骤5：Redis 运行时故障（连接拒绝/超时等）→ 降级按策略返回 + warn
            // 不向上抛：幂等辅助是「防重复」增强件，故障时不应阻断消费主流程
            log.warn("Redis 访问失败，MQ 幂等去重降级（allowOnDegraded={}），queue={}，eventId={}，cause={}",
                    allowOnDegraded, queue, eventId, e.getMessage());
            return allowOnDegraded;
        }
    }
}