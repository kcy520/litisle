package com.litisle.litislereview.moderation.consumer;

import com.litisle.commonmq.consume.MqAckHelper;
import com.litisle.commonmq.consume.MqTraceSupport;
import com.litisle.litisleapi.constant.MqConstant;
import com.litisle.litisleapi.event.moderation.TextSubmitEvent;
import com.litisle.litislereview.moderation.service.ModerationTextService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 文本送审消费者（板块三 3-1，§4.2.1 ④⑤⑥）
 *
 * 【职责】监听 moderation.text.submit 队列，按手动 ACK 纪律消费文本送审消息；
 * 本类只做「消费编排」（traceId 恢复 + ACK 模板），审核业务全部委托
 * {@link ModerationTextService#process(TextSubmitEvent)}。
 *
 * 【消息体】TextSubmitEvent（eventId/modType/bizType/bizId/scene/contentHash/retryCount/traceId）；
 * 事件体不含正文——正文由消费端按 bizType+bizId+scene 回查（防事件体被篡改注入）。
 *
 * 【prefetch】由 yml 统一配置 spring.rabbitmq.listener.simple.prefetch
 * = ${litisle.moderation.consumer.prefetch:10}（@RabbitListener 无 prefetch 属性，属容器级配置）。
 *
 * 【ACK 纪律】（复用 common-mq MqAckHelper，禁止手写 try-catch-ack-nack）
 * - 业务成功 → ack
 * - 业务性失败（BizException，如缺 scene/任务缺失/hash 错配）→ warn 后 ack 丢弃（防毒丸）
 * - 非预期异常（回查失败/DB 超时/发布失败）→ error 后 nack(requeue=false) 经 DLX 入 DLQ
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationTextConsumer {

    /** 文本审核编排服务（3-1 业务逻辑载体） */
    private final ModerationTextService moderationTextService;

    /**
     * 消费文本送审消息（消费编排模板；ACK 纪律与 traceId 恢复收口 common-mq）
     *
     * @param event   文本送审事件（litisle-api 契约）
     * @param message 原始消息（取 deliveryTag / traceId Header）
     * @param channel 消费通道（ack/nack）
     */
    @RabbitListener(queues = MqConstant.QUEUE_MODERATION_TEXT_SUBMIT)
    public void onTextSubmit(TextSubmitEvent event, Message message, Channel channel) throws IOException {
        // 步骤1：从消息 Header 恢复 traceId 到 MDC（消费线程日志自动带链路 ID，补齐 HTTP→MQ 链路缺口）
        MqTraceSupport.restore(message);
        try {
            // 步骤2：手动 ACK 模板收口 common-mq（禁止手写 try-catch-ack-nack）：
            //        - 业务成功 → basicAck
            //        - BizException（缺 scene/任务缺失/hash 错配等业务性失败）→ warn 后 ack 丢弃（防毒丸）
            //        - 其他异常（回查失败/DB 超时/发布失败）→ error 后 nack(requeue=false) 经 DLX 入 DLQ 兜底
            // 步骤3：委托审核编排服务执行业务（定位任务/幂等分流/缓存查重/分片送审/三分态落库/发 result 事件）
            MqAckHelper.executeWithAck(MqConstant.QUEUE_MODERATION_TEXT_SUBMIT, message, channel,
                    () -> moderationTextService.process(event));
        } finally {
            // 步骤4：清理 MDC（消费线程由容器复用，不清除会串扰下一条消息的日志链路 ID）
            MqTraceSupport.clear();
        }
    }
}