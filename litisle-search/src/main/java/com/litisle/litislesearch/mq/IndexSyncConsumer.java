package com.litisle.litislesearch.mq;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commonmq.consume.MqAckHelper;
import com.litisle.commonmq.consume.MqTraceSupport;
import com.litisle.commonmq.metrics.MqMetrics;
import com.litisle.commonmq.send.MqSendUtil;
import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litisleapi.constant.MqConstant;
import com.litisle.litisleapi.event.content.ContentArticleUpdatedEvent;
import com.litisle.litisleapi.event.content.ContentBookChangedEvent;
import com.litisle.litisleapi.event.content.ContentPublishedEvent;
import com.litisle.litisleapi.event.content.ContentUnpublishedEvent;
import com.litisle.litisleapi.event.user.UserProfileUpdatedEvent;
import com.litisle.litisleapi.event.user.UserRegisteredEvent;
import com.litisle.litislesearch.config.SearchProperties;
import com.litisle.litislesearch.constant.SearchBizConstant;
import com.litisle.litislesearch.service.ArticleIndexService;
import com.litisle.litislesearch.service.BookIndexService;
import com.litisle.litislesearch.service.HotQueryCacheService;
import com.litisle.litislesearch.service.UserIndexService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 索引同步消费者（批次二文章 / 批次五书籍 B4 + 用户 B5）。
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
 *
 * 【热点缓存失效（批次四接入）】三个事件处理成功后都调用 HotQueryCacheService#evictAll：
 * 发布/下架/元数据更新都会改变检索结果，缓存不清会让前端在 TTL 内看到旧数据；
 * 其中下架属隐私红线，失效失败抛 IllegalStateException 交由 MQ 延迟重试，保证缓存最终被清理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexSyncConsumer {

    /** 文章索引同步编排服务（回查、门控、写入/删除/局部更新的业务载体） */
    private final ArticleIndexService articleIndexService;

    /** 书籍索引同步服务（批次五 B4：book_idx 回查后 upsert/删除） */
    private final BookIndexService bookIndexService;

    /** 用户索引同步服务（批次五 B5：user_idx 回查后 upsert/删除） */
    private final UserIndexService userIndexService;

    /** search 配置（消费重试参数：最大次数与退避基数，外置 yml/Nacos 可调） */
    private final SearchProperties properties;

    /** MQ 发送工具（异常时向延迟交换机重投，由 x-delayed 插件按 x-delay 头延迟投递） */
    private final MqSendUtil mqSendUtil;

    /** 热点查询缓存服务（索引变更后全量失效，批次四接入） */
    private final HotQueryCacheService hotQueryCacheService;

    /** MQ 消费指标（批次八 8.2：按队列与消费结果记账，MeterRegistry 缺失时静默空转） */
    private final MqMetrics mqMetrics;

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
                        // 步骤4-5：委托业务服务 + 缓存失效，并按消费结果记账（成功/业务丢弃/非预期）
                        runWithMetrics(MqConstant.QUEUE_CONTENT_PUBLISHED, () -> {
                            // 内部完成回查、乱序门控、upsert 与切片重建
                            articleIndexService.handlePublished(articleId);
                            // 索引已变化，失效热点查询缓存（失败按可恢复故障抛出，交由延迟重试）
                            hotQueryCacheService.evictAll();
                        });
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
                        // 下架删除不区分 reason：任何一条下架路径都必须让索引与切片消失（隐私红线）；
                        // 索引删除与缓存失效一起按消费结果记账
                        runWithMetrics(MqConstant.QUEUE_CONTENT_UNPUBLISHED, () -> {
                            articleIndexService.handleUnpublished(articleId);
                            // 索引已删除，必须同步失效热点缓存（失败抛出触发重试，防已下架内容在缓存中残留）
                            hotQueryCacheService.evictAll();
                        });
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
                        // 元数据更新与缓存失效一起按消费结果记账；业务服务内部会二次回查门控：
                        // 文章已下架/转私密则转为清理处理
                        runWithMetrics(MqConstant.QUEUE_CONTENT_ARTICLE_UPDATED, () -> {
                            articleIndexService.handleArticleUpdated(articleId);
                            // 元数据已更新，失效热点查询缓存，保证检索结果与索引一致
                            hotQueryCacheService.evictAll();
                        });
                    });
        } finally {
            MqTraceSupport.clear();
        }
    }

    /**
     * 消费书籍索引变更事件（批次五 B4）：回查书籍权威数据后 upsert 或删除 book_idx。
     *
     * <p>事件体只带 bookId；书籍创建/编辑/完结/下架/删除统一走本监听，
     * 消费端门控（未删除且非下架）决定写入还是删除。</p>
     *
     * @param event   书籍变更事件
     * @param message 原始消息（取 deliveryTag 与 traceId、retryCount 消息头）
     * @param channel 消费通道（ack/nack）
     * @throws IOException ack/nack 失败向上抛出
     */
    @RabbitListener(queues = MqConstant.QUEUE_CONTENT_BOOK_CHANGED)
    public void onBookChanged(ContentBookChangedEvent event, Message message, Channel channel) throws IOException {
        // 恢复 traceId，串联 content → MQ → search 全链路日志
        MqTraceSupport.restore(message);
        try {
            MqAckHelper.executeWithRetry(MqConstant.QUEUE_CONTENT_BOOK_CHANGED, message, channel,
                    properties.getRetry().getMaxCount(),
                    properties.getRetry().getBaseDelayMs(),
                    // 延迟重投动作：重试次数只通过消息头双写传递
                    (newRetryCount, delayMs) -> mqSendUtil.sendDelayed(SearchBizConstant.EXCHANGE_SEARCH_DELAY,
                            MqConstant.QUEUE_CONTENT_BOOK_CHANGED, event, delayMs, newRetryCount),
                    () -> {
                        // 定位键缺失属于发送侧契约违约，重试无意义 → warn 后正常返回（ack 丢弃）
                        if (event.getBookId() == null) {
                            log.warn("[索引同步] 书籍事件缺少 bookId，跳过处理：eventId={}", event.getEventId());
                            return;
                        }
                        // 委托业务服务：回查、门控、upsert / 删除；与缓存失效一起按消费结果记账
                        runWithMetrics(MqConstant.QUEUE_CONTENT_BOOK_CHANGED, () -> {
                            bookIndexService.handleChanged(event.getBookId());
                            // 书籍索引已变化，失效热点查询缓存（失败按可恢复故障抛出，交延迟重试）
                            hotQueryCacheService.evictAll();
                        });
                    });
        } finally {
            // 清理 MDC，防消费线程复用导致 traceId 串扰
            MqTraceSupport.clear();
        }
    }

    /**
     * 消费用户注册事件（批次五 B5）：回查用户公开资料，满足门控才建 user_idx 文档。
     *
     * <p>新用户通常尚无笔名/简介，门控不通过即删除（幂等空操作），
     * 待资料更新事件再正式入索引。</p>
     *
     * @param event   用户注册事件
     * @param message 原始消息
     * @param channel 消费通道
     * @throws IOException ack/nack 失败向上抛出
     */
    @RabbitListener(queues = MqConstant.QUEUE_USER_REGISTERED_INDEX)
    public void onUserRegistered(UserRegisteredEvent event, Message message, Channel channel) throws IOException {
        consumeUserIndex(MqConstant.QUEUE_USER_REGISTERED_INDEX, event.getUserId(), event, message, channel);
    }

    /**
     * 消费用户资料更新事件（批次五 B5）：回查用户公开资料，upsert 或删除 user_idx。
     *
     * @param event   用户资料更新事件
     * @param message 原始消息
     * @param channel 消费通道
     * @throws IOException ack/nack 失败向上抛出
     */
    @RabbitListener(queues = MqConstant.QUEUE_USER_PROFILE_UPDATED_INDEX)
    public void onUserProfileUpdated(UserProfileUpdatedEvent event, Message message, Channel channel) throws IOException {
        consumeUserIndex(MqConstant.QUEUE_USER_PROFILE_UPDATED_INDEX, event.getUserId(), event, message, channel);
    }

    /**
     * 用户索引同步消费统一模板（注册/资料更新共用，避免两份重复的 ACK/重试编排）。
     *
     * @param queueName 源队列名（用于延迟重投路由键与重试计数消息头）
     * @param userId    用户 ID（定位键）
     * @param event     事件体（延迟重投时原样回投）
     * @param message   原始消息
     * @param channel   消费通道
     * @throws IOException ack/nack 失败向上抛出
     */
    private void consumeUserIndex(String queueName, Long userId, Object event, Message message, Channel channel)
            throws IOException {
        MqTraceSupport.restore(message);
        try {
            MqAckHelper.executeWithRetry(queueName, message, channel,
                    properties.getRetry().getMaxCount(),
                    properties.getRetry().getBaseDelayMs(),
                    // 延迟重投动作：重试次数只通过消息头双写传递
                    (newRetryCount, delayMs) -> mqSendUtil.sendDelayed(SearchBizConstant.EXCHANGE_SEARCH_DELAY,
                            queueName, event, delayMs, newRetryCount),
                    () -> {
                        // 定位键缺失属于发送侧契约违约，重试无意义 → warn 后正常返回（ack 丢弃）
                        if (userId == null) {
                            log.warn("[索引同步] 用户事件缺少 userId，跳过处理：queue={}", queueName);
                            return;
                        }
                        // 委托业务服务：回查、门控、upsert / 删除；与缓存失效一起按消费结果记账
                        runWithMetrics(queueName, () -> {
                            userIndexService.handleUpsert(userId);
                            // 用户索引已变化，失效热点查询缓存（失败按可恢复故障抛出，交延迟重试）
                            hotQueryCacheService.evictAll();
                        });
                    });
        } finally {
            // 清理 MDC，防消费线程复用导致 traceId 串扰
            MqTraceSupport.clear();
        }
    }

    /**
     * 执行索引同步动作并按消费结果记录 MQ 指标（批次八 8.2）。
     *
     * <p>指标口径与 MqAckHelper 的 ACK 三分支对齐：正常完成=SUCCESS；
     * 抛 BizException=业务性失败（随后 ack 丢弃）=BIZ_DROP；
     * 抛其他异常=非预期故障（随后延迟重试/最终入 DLQ）=UNEXPECTED。
     * 异常原样抛出，不改变 MqAckHelper 原有的 ACK/重试分流。</p>
     *
     * @param queue  当前消费队列（MqConstant 常量，指标标签基数有限）
     * @param action 索引同步业务动作（含缓存失效）
     */
    private void runWithMetrics(String queue, Runnable action) {
        try {
            action.run();
            mqMetrics.recordConsume(queue, MqMetrics.ConsumeResult.SUCCESS);
        } catch (BizException e) {
            // 业务性失败：MqAckHelper 会 ack 丢弃，这里先记账再原样抛出
            mqMetrics.recordConsume(queue, MqMetrics.ConsumeResult.BIZ_DROP);
            throw e;
        } catch (RuntimeException e) {
            // 非预期异常：MqAckHelper 会延迟重投或入 DLQ，这里先记账再原样抛出
            mqMetrics.recordConsume(queue, MqMetrics.ConsumeResult.UNEXPECTED);
            throw e;
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
