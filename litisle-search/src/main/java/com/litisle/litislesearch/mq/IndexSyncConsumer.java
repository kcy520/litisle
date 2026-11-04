package com.litisle.litislesearch.mq;

import com.litisle.commonmq.consume.MqAckHelper;
import com.litisle.commonmq.consume.MqTraceSupport;
import com.litisle.commonmq.send.MqSendUtil;
import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litisleapi.constant.MqConstant;
import com.litisle.litisleapi.event.content.ContentArticleUpdatedEvent;
import com.litisle.litisleapi.event.content.ContentPublishedEvent;
import com.litisle.litisleapi.event.content.ContentUnpublishedEvent;
import com.litisle.litislesearch.config.SearchProperties;
import com.litisle.litislesearch.constant.SearchBizConstant;
import com.litisle.litislesearch.service.ArticleIndexService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 文章索引同步消费者（批次二 2.2 / 2.3 / 2.4）。
 *
 * 【职责】监听 content 侧三个文章事件队列，把消息转成 article_idx / article_chunks 的写入动作，
 * 业务逻辑全部委托 {@link ArticleIndexService}，本类只做「消费编排」：
 * traceId 恢复 + 业务类型校验 + ACK/重试模板。
 *
 * 【消息体纪律】事件体只携带定位键（bizType / bizId / contentHash），正文与标题等元数据
 * 一律由 ArticleIndexService 通过 ContentClient 回查权威值（防事件体被篡改或字段过期）。
 *
 * 【ACK 与重试纪律】（复用 common-mq MqAckHelper，禁止手写 try-catch-ack-nack）
 * - 业务成功 → ack
 * - 业务性失败（BizException，如文章已不存在）→ warn 后 ack 丢弃（防毒丸消息无限重投）
 * - 非预期异常（ES 故障 / content 服务超时等可恢复故障）→ 指数退避延迟重投：
 *   未耗尽时投到 litisle.search.delay.exchange（routingKey=队列名），延迟后落回原队列；
 *   重试耗尽或重投失败 → nack requeue=false 经 DLX 汇入 litisle.search.index.dlq.queue
 *
 * 【幂等】三类处理都以 articleId 为幂等键（upsert / delete / partial update），
 * 重复消费结果一致，因此不额外使用 SETNX 去重（占位会误伤失败重试的消息）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexSyncConsumer {

    /** 文章索引同步编排服务（回查、门控、写入/删除/局部更新的业务载体） */
    private final ArticleIndexService articleIndexService;

    /** search 配置（消费重试参数：最大次数与退避基数，外置 yml/Nacos 可调） */
    private final SearchProperties properties;

    /** MQ 发送工具（异常时向延迟交换机重投，由 x-delayed 插件按 x-delay 头延迟投递） */
    private final MqSendUtil mqSendUtil;

    /**
     * 消费文章发布事件：写文章索引文档 + 重建 RAG 切片。
     *
     * @param event   文章发布事件（内容侧契约）
     * @param message 原始消息（取 deliveryTag 与 traceId、retryCount 消息头）
     * @param channel 消费通道（ack/nack）
     * @throws IOException ack/nack 失败向上抛出，交由容器按连接异常处理
     */
    @RabbitListener(queues = MqConstant.QUEUE_CONTENT_PUBLISHED)
    public void onPublished(ContentPublishedEvent event, Message message, Channel channel) throws IOException {
        // 步骤1：从消息头恢复 traceId，让消费线程日志能串起 content → MQ → search 全链路
        MqTraceSupport.restore(message);
        try {
            // 步骤2：按重试感知的 ACK 模板执行业务（异常分流规则见类注释）
            MqAckHelper.executeWithRetry(MqConstant.QUEUE_CONTENT_PUBLISHED, message, channel,
                    properties.getRetry().getMaxCount(),
                    properties.getRetry().getBaseDelayMs(),
                    // 延迟重投动作：事件体不携带 retryCount，重试次数只通过消息头双写传递
                    (newRetryCount, delayMs) -> mqSendUtil.sendDelayed(SearchBizConstant.EXCHANGE_SEARCH_DELAY,
                            MqConstant.QUEUE_CONTENT_PUBLISHED, event, delayMs, newRetryCount),
                    () -> {
                        // 步骤3：校验业务类型并取文章 ID（非文章消息直接丢弃）
                        Long articleId = resolveArticleId(event.getBizType(), event.getBizId(), event.getEventId());
                        if (articleId == null) {
                            return;
                        }
                        // 步骤4：委托业务服务（内部完成回查、乱序门控、upsert 与切片重建）
                        articleIndexService.handlePublished(articleId);
                    });
        } finally {
            // 步骤5：清理 MDC，防消费线程复用导致 traceId 串扰下一条消息
            MqTraceSupport.clear();
        }
    }

    /**
     * 消费内容下架事件（删除/下架/转私密/封禁连带四条路径共用）：删文章索引文档 + 删全部切片。
     *
     * @param event   内容下架事件（含 reason 下架原因，当前统一按删除处理）
     * @param message 原始消息
     * @param channel 消费通道
     * @throws IOException ack/nack 失败向上抛出
     */
    @RabbitListener(queues = MqConstant.QUEUE_CONTENT_UNPUBLISHED)
    public void onUnpublished(ContentUnpublishedEvent event, Message message, Channel channel) throws IOException {
        MqTraceSupport.restore(message);
        try {
            MqAckHelper.executeWithRetry(MqConstant.QUEUE_CONTENT_UNPUBLISHED, message, channel,
                    properties.getRetry().getMaxCount(),
                    properties.getRetry().getBaseDelayMs(),
                    (newRetryCount, delayMs) -> mqSendUtil.sendDelayed(SearchBizConstant.EXCHANGE_SEARCH_DELAY,
                            MqConstant.QUEUE_CONTENT_UNPUBLISHED, event, delayMs, newRetryCount),
                    () -> {
                        Long articleId = resolveArticleId(event.getBizType(), event.getBizId(), event.getEventId());
                        if (articleId == null) {
                            return;
                        }
                        // 下架删除不区分 reason：任何一条下架路径都必须让索引与切片消失（隐私红线）
                        articleIndexService.handleUnpublished(articleId);
                    });
        } finally {
            MqTraceSupport.clear();
        }
    }

    /**
     * 消费已发布文章元数据编辑事件：只局部更新标题/标签/摘要等元数据，不动正文、向量与切片。
     *
     * @param event   元数据更新事件
     * @param message 原始消息
     * @param channel 消费通道
     * @throws IOException ack/nack 失败向上抛出
     */
    @RabbitListener(queues = MqConstant.QUEUE_CONTENT_ARTICLE_UPDATED)
    public void onArticleUpdated(ContentArticleUpdatedEvent event, Message message, Channel channel) throws IOException {
        MqTraceSupport.restore(message);
        try {
            MqAckHelper.executeWithRetry(MqConstant.QUEUE_CONTENT_ARTICLE_UPDATED, message, channel,
                    properties.getRetry().getMaxCount(),
                    properties.getRetry().getBaseDelayMs(),
                    (newRetryCount, delayMs) -> mqSendUtil.sendDelayed(SearchBizConstant.EXCHANGE_SEARCH_DELAY,
                            MqConstant.QUEUE_CONTENT_ARTICLE_UPDATED, event, delayMs, newRetryCount),
                    () -> {
                        Long articleId = resolveArticleId(event.getBizType(), event.getBizId(), event.getEventId());
                        if (articleId == null) {
                            return;
                        }
                        // 业务服务内部会二次回查门控：文章已下架/转私密则转为清理处理
                        articleIndexService.handleArticleUpdated(articleId);
                    });
        } finally {
            MqTraceSupport.clear();
        }
    }

    /**
     * 校验并取出文章 ID（只处理 bizType=1 的文章消息）。
     *
     * @param bizType 业务类型（1-文章，其余类型本消费者不处理）
     * @param bizId   业务 ID（文章 ID）
     * @param eventId 事件 ID（仅用于日志定位）
     * @return 文章 ID；非文章消息或 ID 缺失时返回 null（由调用方正常返回即 ack 丢弃）
     */
    private Long resolveArticleId(Integer bizType, Long bizId, String eventId) {
        // 非文章消息：本消费者无处理能力，记录 warn 后丢弃，避免占用重试次数
        if (bizType == null || bizType != ModerationConstant.BIZ_TYPE_ARTICLE) {
            log.warn("[索引同步] 非文章事件，跳过处理：eventId={}, bizType={}", eventId, bizType);
            return null;
        }
        // 定位键缺失属于发送侧契约违约，重试也无意义，记录 warn 后丢弃
        if (bizId == null) {
            log.warn("[索引同步] 事件缺少 bizId，跳过处理：eventId={}", eventId);
            return null;
        }
        return bizId;
    }
}
