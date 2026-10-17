package com.litisle.litislecontent.mq.consumer;

import com.litisle.commonmq.consume.MqAckHelper;
import com.litisle.commonmq.consume.MqTraceSupport;
import com.litisle.commonmq.send.MqSendUtil;
import com.litisle.litisleapi.constant.MqConstant;
import com.litisle.litisleapi.event.moderation.ModerationAppealResultEvent;
import com.litisle.litislecontent.config.ContentProperties;
import com.litisle.litislecontent.service.ArticleReviewWriteBackService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 文章申诉改判结果消费者（板块三 3-5 引用 5-4.5 口径）。
 *
 * 【职责】监听 content 侧申诉改判队列（绑定 moderation.appeal.result），按手动 ACK 纪律消费；
 * 本类只做「消费编排」，改判业务全部委托
 * {@link ArticleReviewWriteBackService#handleAppeal(ModerationAppealResultEvent)}。
 *
 * 【业务面】appealPass=true 时：删 last-hash 免审键（防 REJECT_HINT 误提示）+
 * CAS 3→2 恢复发布 + 发 content.published；维持驳回无操作 ack（5-4.5：平台结论反转以最新为准）
 *
 * 【ACK 纪律】同 {@link ModerationResultConsumer}（MqAckHelper 收口：成功 ack /
 * 业务性失败丢弃 / 非预期异常指数退避重投，耗尽入 DLQ 兜底）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationAppealResultConsumer {

    /** 文章审核结果回写服务（申诉改判业务载体：last-hash 失效 + CAS 恢复发布） */
    private final ArticleReviewWriteBackService articleReviewWriteBackService;

    /** 消费端可调参数（moderation.retry.*：重试上限/指数退避基数，外置 yml/Nacos 运营可调） */
    private final ContentProperties contentProperties;

    /** MQ 发送工具（延迟重投走延迟交换机 x-delayed 插件，指数退避落回本队列） */
    private final MqSendUtil mqSendUtil;

    /**
     * 消费申诉改判消息（消费编排模板；ACK 纪律与 traceId 恢复收口 common-mq）
     *
     * @param event   申诉改判事件（litisle-api 契约）
     * @param message 原始消息（取 deliveryTag / traceId Header）
     * @param channel 消费通道（ack/nack）
     */
    @RabbitListener(queues = MqConstant.QUEUE_CONTENT_MODERATION_APPEAL_RESULT)
    public void onAppeal(ModerationAppealResultEvent event, Message message, Channel channel) throws IOException {
        // 步骤1：从消息 Header 恢复 traceId 到 MDC（消费线程日志自动带链路 ID）
        MqTraceSupport.restore(message);
        try {
            // 步骤2：重试感知 ACK 模板收口 common-mq（同 ModerationResultConsumer 口径）
            // 步骤3：委托回写服务执行改判业务（appealPass 分流→删免审键→CAS 3→2→发 published）
            MqAckHelper.executeWithRetry(MqConstant.QUEUE_CONTENT_MODERATION_APPEAL_RESULT, message, channel,
                    contentProperties.getModeration().getRetry().getMaxCount(),
                    contentProperties.getModeration().getRetry().getBaseDelayMs(),
                    // 延迟重投闭包：改判事件体无 retryCount 字段，原样重发（CAS 恢复发布幂等）
                    (newRetryCount, delayMs) -> mqSendUtil.sendDelayed(MqConstant.EXCHANGE_MODERATION_DELAY,
                            MqConstant.ROUTING_MODERATION_APPEAL_RESULT, event, delayMs, newRetryCount),
                    () -> articleReviewWriteBackService.handleAppeal(event));
        } finally {
            // 步骤4：清理 MDC（消费线程由容器复用，不清除会串扰下一条消息的日志链路 ID）
            MqTraceSupport.clear();
        }
    }
}