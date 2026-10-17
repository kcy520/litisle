package com.litisle.litislecontent.mq.consumer;

import com.litisle.commonmq.consume.MqAckHelper;
import com.litisle.commonmq.consume.MqTraceSupport;
import com.litisle.commonmq.send.MqSendUtil;
import com.litisle.litisleapi.constant.MqConstant;
import com.litisle.litisleapi.event.moderation.ModerationResultEvent;
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
 * 文章审核结果回写消费者（板块三 3-5，§4.5.2）。
 *
 * 【职责】监听 content 侧审核结果队列（绑定 moderation.text.result），按手动 ACK 纪律消费；
 * 本类只做「消费编排」（traceId 恢复 + ACK 模板），聚合回写业务全部委托
 * {@link ArticleReviewWriteBackService#handleResult(ModerationResultEvent)}。
 *
 * 【消息面】同队列混收文章双链路结果：文本机审/人审 result（scene=ARTICLE）与
 * 配图复审 result（scene=ARTICLE_IMG_RE_REVIEW，bizType 仍=1 复用文章任务），
 * 消费端按 scene 分流进聚合槽；评论 bizType=2 随评论模块开发接线（当前留痕丢弃）
 *
 * 【prefetch】由 yml 统一配置 spring.rabbitmq.listener.simple.prefetch（容器级配置）
 *
 * 【ACK 纪律】（复用 common-mq MqAckHelper，禁止手写 try-catch-ack-nack/重投模板）
 * - 业务成功 → ack
 * - 业务性失败（BizException，如 bizId 非法/scene 未知等留痕丢弃场景）→ warn 后 ack 丢弃（防毒丸）
 * - 非预期异常（DB 超时/Redis 异常/发事件失败）→ 指数退避延迟重试（3-8.1 同口径：未耗尽经
 *   延迟交换机重投回本队列后 ack 原消息；耗尽或重投失败 → nack 入 DLQ 兜底，绝不自动放行）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationResultConsumer {

    /** 文章审核结果回写服务（3-5 业务逻辑载体：聚合判定/CAS 回写/last-hash 写入） */
    private final ArticleReviewWriteBackService articleReviewWriteBackService;

    /** 消费端可调参数（moderation.retry.*：重试上限/指数退避基数，外置 yml/Nacos 运营可调） */
    private final ContentProperties contentProperties;

    /** MQ 发送工具（延迟重投走延迟交换机 x-delayed 插件，指数退避落回本队列） */
    private final MqSendUtil mqSendUtil;

    /**
     * 消费审核结果消息（消费编排模板；ACK 纪律与 traceId 恢复收口 common-mq）
     *
     * @param event   审核结果事件（litisle-api 契约；文章双链路 scene 混合同队列到达）
     * @param message 原始消息（取 deliveryTag / traceId Header）
     * @param channel 消费通道（ack/nack）
     */
    @RabbitListener(queues = MqConstant.QUEUE_CONTENT_MODERATION_TEXT_RESULT)
    public void onResult(ModerationResultEvent event, Message message, Channel channel) throws IOException {
        // 步骤1：从消息 Header 恢复 traceId 到 MDC（消费线程日志自动带链路 ID，补齐 MQ→MQ 链路缺口）
        MqTraceSupport.restore(message);
        try {
            // 步骤2：重试感知 ACK 模板收口 common-mq（同 ModerationTextConsumer 口径）
            // 步骤3：委托回写服务执行业务（bizType/scene 分流→陈旧性防线→聚合槽→CAS 回写→last-hash 写入）
            MqAckHelper.executeWithRetry(MqConstant.QUEUE_CONTENT_MODERATION_TEXT_RESULT, message, channel,
                    contentProperties.getModeration().getRetry().getMaxCount(),
                    contentProperties.getModeration().getRetry().getBaseDelayMs(),
                    // 延迟重投闭包：result 事件体无 retryCount 字段（契约不含），原样重发即可——
                    // 回写链路 CAS+槽写+键写全幂等，重复消费安全
                    (newRetryCount, delayMs) -> mqSendUtil.sendDelayed(MqConstant.EXCHANGE_MODERATION_DELAY,
                            MqConstant.ROUTING_MODERATION_TEXT_RESULT, event, delayMs, newRetryCount),
                    () -> articleReviewWriteBackService.handleResult(event));
        } finally {
            // 步骤4：清理 MDC（消费线程由容器复用，不清除会串扰下一条消息的日志链路 ID）
            MqTraceSupport.clear();
        }
    }
}