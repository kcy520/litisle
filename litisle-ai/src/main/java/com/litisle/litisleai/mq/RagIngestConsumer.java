package com.litisle.litisleai.mq;

import com.litisle.commonmq.consume.MqAckHelper;
import com.litisle.commonmq.consume.MqTraceSupport;
import com.litisle.commonmq.send.MqSendUtil;
import com.litisle.litisleai.config.AiRagProperties;
import com.litisle.litisleai.service.RagIngestService;
import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litisleapi.constant.MqConstant;
import com.litisle.litisleapi.event.content.ContentPublishedEvent;
import com.litisle.litisleapi.event.content.ContentUnpublishedEvent;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * RAG 摄入消费者（板块四 4.1：AI 侧独立消费 content.published / content.unpublished）
 *
 * <p>【消息体纪律】事件体只携带定位键（bizType/bizId），可见性与正文一律由
 * RagIngestService 经 ContentClient 回查权威值（P8 定档：正文经 ContentClient 回查）。</p>
 *
 * <p>【ACK 与重试纪律】（复用 common-mq MqAckHelper，与 search IndexSyncConsumer 同构）
 * 业务成功 → ack；业务性失败（BizException，如文章已不存在）→ warn 后 ack 丢弃（防毒丸）；
 * 非预期异常（embedding/ES/回查超时等可恢复故障）→ 延迟重投（AI 侧 x-delayed 交换机），
 * 重试耗尽 → nack requeue=false 经 DLX 汇入 AI RAG 死信队列。</p>
 *
 * <p>【幂等】摄入按 chunkId=articleId_index 覆盖写（先删后写整篇），下架删除幂等空操作，
 * 重复消费结果一致，不额外使用去重键。</p>
 *
 * <p>【条件装配】rag.enabled=false 时消费者不装配（本地无 Ollama/ES 不消费）。</p>
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "litisle.ai.rag", name = "enabled", havingValue = "true")
public class RagIngestConsumer {

    /** 摄入重试参数（最大次数，对齐 search 消费惯例；基数延迟固定 5 秒起步） */
    private static final int RETRY_MAX_COUNT = 3;
    private static final long RETRY_BASE_DELAY_MS = 5_000L;

    /** 摄入编排服务（门控回查/切片/向量化/索引写入的业务载体） */
    private final RagIngestService ragIngestService;

    /** MQ 发送工具（异常时向 AI 延迟交换机重投） */
    private final MqSendUtil mqSendUtil;

    /** RAG 配置（enabled 条件来源；注入即校验配置存在性） */
    private final AiRagProperties ragProperties;

    /**
     * 消费文章发布事件：门控回查 → 切片向量化 → 覆盖写 AI 自管切片索引
     *
     * @param event   文章发布事件（content 侧契约）
     * @param message 原始消息（取 deliveryTag 与 traceId/retryCount 消息头）
     * @param channel 消费通道（ack/nack）
     * @throws IOException ack/nack 失败向上抛出，交由容器按连接异常处理
     */
    @RabbitListener(queues = MqConstant.QUEUE_AI_RAG_PUBLISHED)
    public void onPublished(ContentPublishedEvent event, Message message, Channel channel) throws IOException {
        /*
         * 业务逻辑步骤：
         * 1. 从消息头恢复 traceId，串起 content → MQ → ai 全链路日志；
         * 2. 交由 MqAckHelper 重试感知模板执行：成功 ack / 业务性失败丢弃 / 非预期失败延迟重投；
         * 3. 校验 bizType 并取出文章 ID，非文章消息或定位键缺失直接 ack 丢弃；
         * 4. 委托 RagIngestService 回查门控后摄入切片（含向量）。
         */
        // 回查门控 = 回查（回调查询权威数据） + 门控（准入检查） ，是两个动作的组合，对应 RagIngestService.java 里的 ①② 两步。
        // MQ 事件体`ContentPublishedEvent` 只携带定位键（bizType/bizId）， 不带正文和可见性 。所以消费者拿到文章 ID 后，先通过 Feign 的`contentClient.getContent()` 调回 content 服务，取文章当前的权威数据（正文、status、visibility），见 fetchArticle 。
        // 为什么这么做：事件发出后到消费之间有时间差，消息体里的字段可能已过期（比如发出后又转私密了），也可能被篡改——所以只信 content 服务的实时值，即注释里的「P8 定档：正文经 ContentClient 回查」。

        // 步骤1：恢复 traceId（消费线程日志串联全链路）
        MqTraceSupport.restore(message);
        try {
            // 步骤2：重试感知 ACK 模板（异常分流规则见类注释）
            MqAckHelper.executeWithRetry(MqConstant.QUEUE_AI_RAG_PUBLISHED, message, channel,
                    RETRY_MAX_COUNT,
                    RETRY_BASE_DELAY_MS,
                    // 延迟重投动作：路由键=源队列名，按 x-delay 头延迟后落回原队列
                    (newRetryCount, delayMs) -> mqSendUtil.sendDelayed(MqConstant.EXCHANGE_AI_RAG_DELAY,
                            MqConstant.QUEUE_AI_RAG_PUBLISHED, event, delayMs, newRetryCount),
                    () -> {
                        // 步骤3：校验业务类型并取文章 ID（非文章消息直接丢弃）
                        Long articleId = resolveArticleId(event.getBizType(), event.getBizId(), event.getEventId());
                        if (articleId == null) {
                            return;
                        }
                        // 步骤4：委托摄入编排（门控回查/切片/向量化/索引写入）；ES 异常包装为运行时异常转重试分流
                        try {
                            ragIngestService.handlePublished(articleId);
                        } catch (IOException e) {
                            throw new IllegalStateException("RAG 摄入 ES 操作失败，转消费重试 articleId=" + articleId, e);
                        }
                    });
        } finally {
            // 清理 MDC，防消费线程复用串扰
            MqTraceSupport.clear();
        }
    }

    /**
     * 消费内容下架事件：按篇删除 AI 自管切片（隐私红线：不区分 reason 一律删除）
     *
     * @param event   内容下架事件（含 reason，统一按删除处理）
     * @param message 原始消息
     * @param channel 消费通道
     * @throws IOException ack/nack 失败向上抛出
     */
    @RabbitListener(queues = MqConstant.QUEUE_AI_RAG_UNPUBLISHED)
    public void onUnpublished(ContentUnpublishedEvent event, Message message, Channel channel) throws IOException {
        /*
         * 业务逻辑步骤：
         * 1. 恢复 traceId；2. ACK 模板执行（异常分流规则见类注释）；
         * 3. 校验并取出文章 ID（非文章消息直接丢弃）；
         * 4. 委托 RagIngestService 按篇删除切片（幂等空操作，失败延迟重投保证最终删除）。
         */
        MqTraceSupport.restore(message);
        try {
            MqAckHelper.executeWithRetry(MqConstant.QUEUE_AI_RAG_UNPUBLISHED, message, channel,
                    RETRY_MAX_COUNT,
                    RETRY_BASE_DELAY_MS,
                    (newRetryCount, delayMs) -> mqSendUtil.sendDelayed(MqConstant.EXCHANGE_AI_RAG_DELAY,
                            MqConstant.QUEUE_AI_RAG_UNPUBLISHED, event, delayMs, newRetryCount),
                    () -> {
                        Long articleId = resolveArticleId(event.getBizType(), event.getBizId(), event.getEventId());
                        if (articleId == null) {
                            return;
                        }
                        // 委托按篇删除（幂等空操作）；ES 异常包装为运行时异常转重试分流
                        try {
                            ragIngestService.handleUnpublished(articleId);
                        } catch (java.io.IOException e) {
                            throw new IllegalStateException("RAG 按篇删除 ES 操作失败，转消费重试 articleId=" + articleId, e);
                        }
                    });
        } finally {
            MqTraceSupport.clear();
        }
    }

    /**
     * 校验并取出文章 ID（只处理 bizType=1 的文章消息）
     *
     * @param bizType 业务类型
     * @param bizId   业务 ID
     * @param eventId 事件 ID（日志定位）
     * @return 文章 ID；非文章消息或 ID 缺失返回 null（调用方正常返回即 ack 丢弃）
     */
    private Long resolveArticleId(Integer bizType, Long bizId, String eventId) {
        // 非文章消息：本消费者无处理能力，warn 后丢弃
        if (bizType == null || bizType != ModerationConstant.BIZ_TYPE_ARTICLE) {
            log.warn("[RAG摄入] 非文章事件，跳过处理：eventId={}, bizType={}", eventId, bizType);
            return null;
        }
        // 定位键缺失属发送侧契约违约，重试无意义，warn 后丢弃
        if (bizId == null) {
            log.warn("[RAG摄入] 事件缺少 bizId，跳过处理：eventId={}", eventId);
            return null;
        }
        return bizId;
    }
}
