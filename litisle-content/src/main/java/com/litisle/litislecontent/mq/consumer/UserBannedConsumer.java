package com.litisle.litislecontent.mq.consumer;


import com.litisle.commonmq.consume.MqAckHelper;
import com.litisle.commonmq.consume.MqTraceSupport;
import com.litisle.commonmq.send.MqSendUtil;
import com.litisle.litisleapi.constant.MqConstant;
import com.litisle.litisleapi.event.user.UserBannedEvent;
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
 * 用户封禁连带消费者（板块八 8.2）。
 *
 * 【职责】监听 content 侧封禁连带队列（绑定 user.banned），按手动 ACK 纪律消费；
 * 本类只做「消费编排」，连带下架业务全部委托
 * {@link ArticleReviewWriteBackService#handleUserBanned(UserBannedEvent)}：
 * 该作者全部已发布文章批量置 4-下架 + 逐篇发 content.unpublished + 缓存三键失效 + 审计留痕。
 *
 * 【ACK 纪律】同 {@link ModerationAppealResultConsumer}（MqAckHelper 收口：成功 ack /
 * 业务性失败丢弃 / 非预期异常指数退避重投，耗尽入 DLQ 兜底；连带下架 CAS 与事件/失效
 * 全链路幂等，重试安全）
 *
 * 【发布方】litisle-user 封禁事务提交后发布（UserBannedEvent 契约先行，user 侧接入后续联调）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserBannedConsumer {

    /** 文章审核结果回写服务（封禁连带下架业务载体：批量 CAS + 逐篇事件与缓存失效） */
    private final ArticleReviewWriteBackService articleReviewWriteBackService;

    /** 消费端可调参数（moderation.retry.*：重试上限/指数退避基数，外置 yml/Nacos 运营可调） */
    private final ContentProperties contentProperties;

    /** MQ 发送工具（延迟重投走延迟交换机 x-delayed 插件，指数退避落回本队列） */
    private final MqSendUtil mqSendUtil;

    /**
     * 消费用户封禁消息（消费编排模板；ACK 纪律与 traceId 恢复收口 common-mq）
     *
     * @param event   用户封禁事件（litisle-api 契约）
     * @param message 原始消息（取 deliveryTag / traceId Header）
     * @param channel 消费通道（ack/nack）
     */
    @RabbitListener(queues = MqConstant.QUEUE_CONTENT_USER_BANNED)
    public void onUserBanned(UserBannedEvent event, Message message, Channel channel) throws IOException {
        // 步骤1：从消息 Header 恢复 traceId 到 MDC（消费线程日志自动带链路 ID）
        MqTraceSupport.restore(message);
        try {
            // 步骤2：重试感知 ACK 模板收口 common-mq（同 ModerationAppealResultConsumer 口径）
            // 步骤3：委托回写服务执行封禁连带下架（批量 CAS→逐篇 unpublished+缓存失效→审计留痕）
            MqAckHelper.executeWithRetry(MqConstant.QUEUE_CONTENT_USER_BANNED, message, channel,
                    contentProperties.getModeration().getRetry().getMaxCount(),
                    contentProperties.getModeration().getRetry().getBaseDelayMs(),
                    // 延迟重投闭包：事件体无 retryCount 字段，原样重发（连带下架全链路幂等）
                    (newRetryCount, delayMs) -> mqSendUtil.sendDelayed(MqConstant.EXCHANGE_MODERATION_DELAY,
                            MqConstant.ROUTING_USER_BANNED, event, delayMs, newRetryCount),
                    () -> articleReviewWriteBackService.handleUserBanned(event));
        } finally {
            // 步骤4：清理 MDC（消费线程由容器复用，不清除会串扰下一条消息的日志链路 ID）
            MqTraceSupport.clear();
        }
    }
}
