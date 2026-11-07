package com.litisle.litislesearch.mq;

import com.litisle.commonmq.consume.MqAckHelper;
import com.litisle.commonmq.consume.MqIdempotentHelper;
import com.litisle.commonmq.consume.MqTraceSupport;
import com.litisle.commonmq.send.MqSendUtil;
import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litisleapi.constant.MqConstant;
import com.litisle.litisleapi.event.content.ContentViewCountFlushedEvent;
import com.litisle.litisleapi.event.social.SocialCommentedEvent;
import com.litisle.litisleapi.event.social.SocialLikedEvent;
import com.litisle.litisleapi.event.social.SocialUnlikedEvent;
import com.litisle.litislesearch.config.SearchProperties;
import com.litisle.litislesearch.constant.SearchBizConstant;
import com.litisle.litislesearch.service.HeatPendingService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 互动热度事件消费者（批次三 3.1，批次A A5/A7 定案）。
 *
 * 【职责】监听点赞 / 取消点赞 / 评论 / 浏览量落库四类队列，把互动信号累加到 Redis 热度待刷新键
 * （Hash 结构，见 {@link HeatPendingService}），本类只做消费编排（traceId 恢复 + 幂等 + ACK/重试），
 * 不直写 ES —— 热度刷写由批次三 3.2 定时任务聚合 `_bulk` 完成。
 *
 * 【幂等】
 * - 点赞 / 评论：HINCRBY 自增不可重入，消费前置 `MqIdempotentHelper.tryAcquire`（SETNX + eventId）
 *   拦重复消息；先占位后累加，优先保证「同一事件不重复计数」（ACK 失败重投场景不会多算）
 * - 浏览量：HSET 覆盖写绝对值，天然可重放，不做 eventId 去重
 *
 * 【ACK 与重试纪律】（复用 common-mq MqAckHelper，禁止手写 try-catch-ack-nack）
 * - Redis 累加成功 → ack（保证「先落 Redis 再 ACK」，防热度漂移）
 * - 非预期异常（Redis 连接失败/超时）→ 指数退避延迟重投到 litisle.search.delay.exchange
 *   （routingKey=队列名），耗尽或重投失败 → nack 入 litisle.search.index.dlq.queue
 * - 非文章类消息（bizType≠1，如评论/私信互动）→ warn 后正常返回（ack 丢弃）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeatEventConsumer {

    /** 热度待刷新键服务（Redis Hash 累加，不直写 ES） */
    private final HeatPendingService heatPendingService;

    /** search 配置（消费重试参数） */
    private final SearchProperties properties;

    /** MQ 发送工具（异常时向延迟交换机重投） */
    private final MqSendUtil mqSendUtil;

    /** MQ 幂等去重（点赞/评论自增不可重入，按 eventId 拦截重复消息） */
    private final MqIdempotentHelper mqIdempotentHelper;

    /**
     * 消费点赞事件：待刷新键 like_delta +1。
     *
     * @param event   点赞事件（bizType=1 时 bizId 为文章 ID）
     * @param message 原始消息（deliveryTag / traceId / retryCount 头）
     * @param channel 消费通道
     * @throws IOException ack/nack 失败向上抛出
     */
    @RabbitListener(queues = MqConstant.QUEUE_SOCIAL_LIKED)
    public void onLiked(SocialLikedEvent event, Message message, Channel channel) throws IOException {
        // 步骤1：恢复 traceId，串起 social → MQ → search 链路
        MqTraceSupport.restore(message);
        try {
            // 步骤2：重试感知 ACK 模板（异常分流规则见类注释）
            MqAckHelper.executeWithRetry(MqConstant.QUEUE_SOCIAL_LIKED, message, channel,
                    properties.getRetry().getMaxCount(),
                    properties.getRetry().getBaseDelayMs(),
                    // 延迟重投动作：事件体无 retryCount 字段，重试次数只走消息头
                    (newRetryCount, delayMs) -> mqSendUtil.sendDelayed(SearchBizConstant.EXCHANGE_SEARCH_DELAY,
                            MqConstant.QUEUE_SOCIAL_LIKED, event, delayMs, newRetryCount),
                    () -> {
                        // 步骤3：只处理文章点赞（评论点赞等一律跳过）
                        Long articleId = resolveArticleId(event.getBizType(), event.getBizId(), event.getEventId());
                        if (articleId == null) {
                            return;
                        }
                        // 步骤4：eventId 幂等（自增不可重入；先占位后累加，ACK 失败重投不会重复计数）
                        if (!mqIdempotentHelper.tryAcquire(MqConstant.QUEUE_SOCIAL_LIKED, event.getEventId())) {
                            return;
                        }
                        // 步骤5：Redis 累加成功后才返回 → 由模板 ack；
                        // 批次十一 11.14：按事件携带的 delta 累加（旧消息无 delta 时按 +1 兜底）
                        long likedDelta = event.getDelta() == null ? 1L : event.getDelta().longValue();
                        if (likedDelta == 0) {
                            return;
                        }
                        heatPendingService.applyLikeDelta(articleId, likedDelta);
                    });
        } finally {
            // 步骤6：清理 MDC，防消费线程复用串扰
            MqTraceSupport.clear();
        }
    }

    /**
     * 消费取消点赞事件：待刷新键 like_delta -1（批次十一 11.14）。
     *
     * <p>与点赞事件分队列监听，正负事件可分别观测与重投；幂等、重试、死信链路完全复用
     * MqIdempotentHelper / MqAckHelper / IndexDlqConsumer，不另建。负数写回 article_idx 时的
     * 下限保护在 {@code IndexHeatFlushJob} 统一做（likeCnt 不允许减成负数）。</p>
     *
     * @param event   取消点赞事件（bizType=1 时 bizId 为文章 ID）
     * @param message 原始消息
     * @param channel 消费通道
     * @throws IOException ack/nack 失败向上抛出
     */
    @RabbitListener(queues = MqConstant.QUEUE_SOCIAL_UNLIKED)
    public void onUnliked(SocialUnlikedEvent event, Message message, Channel channel) throws IOException {
        // 步骤1：恢复 traceId，串起 content → MQ → search 链路
        MqTraceSupport.restore(message);
        try {
            // 步骤2：重试感知 ACK 模板（延迟重投复用同一 search.delay，耗尽入 search DLQ）
            MqAckHelper.executeWithRetry(MqConstant.QUEUE_SOCIAL_UNLIKED, message, channel,
                    properties.getRetry().getMaxCount(),
                    properties.getRetry().getBaseDelayMs(),
                    (newRetryCount, delayMs) -> mqSendUtil.sendDelayed(SearchBizConstant.EXCHANGE_SEARCH_DELAY,
                            MqConstant.QUEUE_SOCIAL_UNLIKED, event, delayMs, newRetryCount),
                    () -> {
                        // 步骤3：只处理文章取消点赞（非文章消息 ack 丢弃）
                        Long articleId = resolveArticleId(event.getBizType(), event.getBizId(), event.getEventId());
                        if (articleId == null) {
                            return;
                        }
                        // 步骤4：eventId 幂等（SETNX + eventId，重复投递不重复递减）
                        if (!mqIdempotentHelper.tryAcquire(MqConstant.QUEUE_SOCIAL_UNLIKED, event.getEventId())) {
                            return;
                        }
                        // 步骤5：按事件 delta 累加（取消点赞固定 -1；缺失时也按 -1 兜底）
                        long unlikedDelta = event.getDelta() == null ? -1L : event.getDelta().longValue();
                        if (unlikedDelta == 0) {
                            return;
                        }
                        heatPendingService.applyLikeDelta(articleId, unlikedDelta);
                    });
        } finally {
            // 步骤6：清理 MDC，防消费线程复用串扰
            MqTraceSupport.clear();
        }
    }

    /**
     * 消费评论事件：待刷新键 comment_delta +1。
     *
     * @param event   评论事件
     * @param message 原始消息
     * @param channel 消费通道
     * @throws IOException ack/nack 失败向上抛出
     */
    @RabbitListener(queues = MqConstant.QUEUE_SOCIAL_COMMENTED)
    public void onCommented(SocialCommentedEvent event, Message message, Channel channel) throws IOException {
        MqTraceSupport.restore(message);
        try {
            MqAckHelper.executeWithRetry(MqConstant.QUEUE_SOCIAL_COMMENTED, message, channel,
                    properties.getRetry().getMaxCount(),
                    properties.getRetry().getBaseDelayMs(),
                    (newRetryCount, delayMs) -> mqSendUtil.sendDelayed(SearchBizConstant.EXCHANGE_SEARCH_DELAY,
                            MqConstant.QUEUE_SOCIAL_COMMENTED, event, delayMs, newRetryCount),
                    () -> {
                        Long articleId = resolveArticleId(event.getBizType(), event.getBizId(), event.getEventId());
                        if (articleId == null) {
                            return;
                        }
                        if (!mqIdempotentHelper.tryAcquire(MqConstant.QUEUE_SOCIAL_COMMENTED, event.getEventId())) {
                            return;
                        }
                        heatPendingService.incrementComment(articleId);
                    });
        } finally {
            MqTraceSupport.clear();
        }
    }

    /**
     * 消费浏览量落库事件：待刷新键 view_cnt 覆盖写绝对值。
     *
     * @param event   浏览量落库事件
     * @param message 原始消息
     * @param channel 消费通道
     * @throws IOException ack/nack 失败向上抛出
     */
    @RabbitListener(queues = MqConstant.QUEUE_CONTENT_VIEW_COUNT_FLUSHED)
    public void onViewCountFlushed(ContentViewCountFlushedEvent event, Message message, Channel channel) throws IOException {
        MqTraceSupport.restore(message);
        try {
            MqAckHelper.executeWithRetry(MqConstant.QUEUE_CONTENT_VIEW_COUNT_FLUSHED, message, channel,
                    properties.getRetry().getMaxCount(),
                    properties.getRetry().getBaseDelayMs(),
                    (newRetryCount, delayMs) -> mqSendUtil.sendDelayed(SearchBizConstant.EXCHANGE_SEARCH_DELAY,
                            MqConstant.QUEUE_CONTENT_VIEW_COUNT_FLUSHED, event, delayMs, newRetryCount),
                    () -> {
                        Long articleId = resolveArticleId(event.getBizType(), event.getBizId(), event.getEventId());
                        // 浏览量缺失属发送侧契约违约，重试无意义，warn 后丢弃
                        if (articleId == null || event.getViewCount() == null) {
                            if (event.getViewCount() == null) {
                                log.warn("[热度累加] 浏览量事件缺少 viewCount，跳过：eventId={}", event.getEventId());
                            }
                            return;
                        }
                        // 绝对值覆盖写，天然幂等，不做 eventId 去重
                        heatPendingService.overwriteViewCount(articleId, event.getViewCount());
                    });
        } finally {
            MqTraceSupport.clear();
        }
    }

    /**
     * 校验并取出文章 ID（只处理 bizType=1 的文章互动）。
     *
     * @param bizType 业务类型（1-文章；2-评论等其他类型本消费者不处理）
     * @param bizId   业务 ID（文章 ID）
     * @param eventId 事件 ID（仅日志定位）
     * @return 文章 ID；非文章消息或 ID 缺失时返回 null（调用方正常返回即 ack 丢弃）
     */
    private Long resolveArticleId(Integer bizType, Long bizId, String eventId) {
        // 非文章互动（如给评论点赞/评论的评论）无文章维度，跳过不动热度
        if (bizType == null || bizType != ModerationConstant.BIZ_TYPE_ARTICLE) {
            log.debug("[热度累加] 非文章互动事件，跳过：eventId={}, bizType={}", eventId, bizType);
            return null;
        }
        if (bizId == null) {
            log.warn("[热度累加] 事件缺少 bizId，跳过：eventId={}", eventId);
            return null;
        }
        return bizId;
    }
}
