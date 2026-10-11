package com.litisle.litislereview.moderation.consumer;

import com.litisle.commonmq.consume.MqAckHelper;
import com.litisle.commonmq.consume.MqTraceSupport;
import com.litisle.litisleapi.constant.MqConstant;
import com.litisle.litisleapi.event.moderation.ImageSubmitEvent;
import com.litisle.litisleapi.event.moderation.TextSubmitEvent;
import com.litisle.litislereview.moderation.service.ModerationDlqService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 机审死信消费者（板块三 3-6，§4.2.4）
 *
 * 【DLQ 兑底特性】两条死信队列（text/image submit DLQ）均无 DLX 兑底配置（防死信再死信环路）：
 * 消费 nack(requeue=false) 即物理丢弃、绝不重投——瞬时故障（DB 超时等）丢弃后任务停留 status=0，
 * 由板块六 6-2 补偿重发扫描兑底；业务性失败（BizException）由 MqAckHelper ack 丢弃（防毒丸）。
 *
 * 【职责】监听两条送审死信队列，对重试耗尽的死信执行「转人审三件套」兑底（绝不自动通过）：
 * - QUEUE_MODERATION_TEXT_SUBMIT_DLQ（moderation.text.submit.dlq）→ onTextSubmitDlq
 * - QUEUE_MODERATION_IMAGE_SUBMIT_DLQ（moderation.image.submit.dlq）→ onImageSubmitDlq
 * 死信消息体即原 TextSubmitEvent / ImageSubmitEvent（Jackson2JsonMessageConverter 自动反序列化）。
 *
 * 【落位】与 ModerationTextConsumer / ModerationImageConsumer 同包：本类只做消费编排
 * （traceId 恢复 + ACK 模板，复用 common-mq MqAckHelper/MqTraceSupport），
 * 业务委托 {@link ModerationDlqService}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationDlqConsumer {

    /** 死信转人审编排服务（3-6 业务逻辑载体，moderation/service 包） */
    private final ModerationDlqService moderationDlqService;

    /**
     * 消费文本送审死信（实现时挂 @RabbitListener(queues = MqConstant.QUEUE_MODERATION_TEXT_SUBMIT_DLQ)）
     *
     * @param event   原文本送审事件（死信体）
     * @param message 原始消息（deliveryTag / traceId Header / x-death 重试史）
     */
    @RabbitListener(queues = MqConstant.QUEUE_MODERATION_TEXT_SUBMIT_DLQ)
    public void onTextSubmitDlq(TextSubmitEvent event, Message message, Channel channel) throws IOException {
        // 步骤1：MqTraceSupport.restore(message)——死信 Header 保留原链路 traceId，转人审后可回溯原机审链路
        MqTraceSupport.restore(message);
        try {
            // 步骤2：MqAckHelper.executeWithAck 委托 moderationDlqService.processTextDeadLetter(event)
            //        ★ DLQ 消费失败纪律：一律 ack 不重投（DLQ 无 DLX，nack(requeue=false) 即物理丢弃）——
            //        任务停留 status=0 由板块六 6-2 补偿重发扫描兑底，绝不 default-allow
            MqAckHelper.executeWithAck(MqConstant.QUEUE_MODERATION_TEXT_SUBMIT_DLQ, message, channel,
                    () -> moderationDlqService.processTextDeadLetter(event));
        } finally {
            // 步骤3：finally MqTraceSupport.clear()——消费线程复用防 MDC 串扰
            MqTraceSupport.clear();
        }
    }

    /**
     * 消费图片送审死信（实现时挂 @RabbitListener(queues = MqConstant.QUEUE_MODERATION_IMAGE_SUBMIT_DLQ)）
     *
     * <p>与文本死信同 ACK 模板，差异在业务侧：图片死信多一步 safe_flag=3 联动（见 ModerationDlqService）</p>
     *
     * @param event   原图片送审事件（死信体）
     * @param message 原始消息
     */
    @RabbitListener(queues = MqConstant.QUEUE_MODERATION_IMAGE_SUBMIT_DLQ)
    public void onImageSubmitDlq(ImageSubmitEvent event, Message message, Channel channel) throws IOException {
        // 步骤1~3：与文本死信同 traceId/ACK/MDC 三步模板；业务侧差异（IMAGE 单图直发 SUSPICIOUS 事件 /
        //        配图复审转人审）见 ModerationDlqService.processImageDeadLetter
        MqTraceSupport.restore(message);
        try {
            MqAckHelper.executeWithAck(MqConstant.QUEUE_MODERATION_IMAGE_SUBMIT_DLQ, message, channel,
                    () -> moderationDlqService.processImageDeadLetter(event));
        } finally {
            MqTraceSupport.clear();
        }
    }
}