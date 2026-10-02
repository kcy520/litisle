package com.litisle.commonmq.send;

import com.litisle.commoncore.constant.HeaderConstant;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * MQ 发送工具（common-mq 统一收口，业务服务禁止直接裸调 RabbitTemplate.convertAndSend）。
 *
 * 【能力】
 * 1. 链路追踪：发送前从 MDC 取 traceId 注入消息 Header（HeaderConstant.TRACE_ID，
 *    与 Feign/HTTP 透传同一口径），消费侧由 MqTraceSupport 恢复，补齐 commom-des.md §10 链路图 MQ 缺口
 * 2. 失败口径统一：发送异常仅记 error 不抛（业务事件发布失败不阻断主流程，
 *    由消费方/对账兜底），对齐既有纪律（如 file 侧 image.submit 发布失败不回滚上传）
 *
 * 【使用示例】
 * <pre>
 *     private final MqSendUtil mqSendUtil;
 *
 *     mqSendUtil.send(MqConstant.EXCHANGE_MODERATION,
 *             MqConstant.ROUTING_MODERATION_IMAGE_SUBMIT, event);
 * </pre>
 */
@Slf4j
public class MqSendUtil {

    /** MDC key：与 common-security TraceIdFilter / FeignAuthInterceptor 的 traceId 写入 key 保持一致（SSOT 为日志 pattern %X{traceId}） */
    public static final String TRACE_ID_MDC_KEY = "traceId";

    private final RabbitTemplate rabbitTemplate;

    public MqSendUtil(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 发送事件到指定交换机（traceId 注入 Header；失败仅记 error 不抛）
     *
     * @param exchange   目标交换机（MqConstant.EXCHANGE_*）
     * @param routingKey 路由键（MqConstant.ROUTING_*）
     * @param event      事件体（litisle-api 事件 DTO，经 Jackson2JsonMessageConverter 序列化）
     */
    public void send(String exchange, String routingKey, Object event) {
        String traceId = MDC.get(TRACE_ID_MDC_KEY);
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, event, message -> {
                if (traceId != null && !traceId.isBlank()) {
                    message.getMessageProperties().setHeader(HeaderConstant.TRACE_ID, traceId);
                }
                return message;
            });
            log.info("MQ 事件已发布，exchange={}，routingKey={}，traceId={}", exchange, routingKey, traceId);
        } catch (Exception e) {
            log.error("MQ 事件发布失败（不阻断主流程），exchange={}，routingKey={}，traceId={}",
                    exchange, routingKey, traceId, e);
        }
    }
}