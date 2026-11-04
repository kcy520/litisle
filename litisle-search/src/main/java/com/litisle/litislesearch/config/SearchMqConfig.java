package com.litisle.litislesearch.config;

import com.litisle.litisleapi.constant.MqConstant;
import com.litisle.litislesearch.constant.SearchBizConstant;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * search 模块 MQ 拓扑声明（索引同步消费端）。
 *
 * 【拓扑】
 * - 业务交换机：litisle.content.exchange / litisle.social.exchange（Topic，durable；
 *   发布方为 content / social 模块，此处幂等声明——参数一致不会报 PRECONDITION_FAILED）
 * - 六个索引同步队列（队列名 SSOT 为 MqConstant；文章三类事件消费者已随批次二落地
 *   IndexSyncConsumer，互动热度三类队列消费者已随批次三 3.1 落地 HeatEventConsumer）：
 *   litisle.content.published.queue          ← content.published
 *   litisle.content.unpublished.queue        ← content.unpublished
 *   litisle.content.article.updated.queue    ← content.article-updated
 *   litisle.content.view.count.flushed.queue ← content.view-count-flushed
 *   litisle.social.liked.queue               ← social.liked
 *   litisle.social.commented.queue           ← social.commented
 * - 死信链路：各队列消费失败 nack requeue=false 后，按「队列名」经独立死信交换机
 *   litisle.search.dlx（Direct）统一汇入 litisle.search.index.dlq.queue，
 *   供人工排查/重投（Direct 精确匹配无通配语义，防业务交换机通配绑定误吸死信）
 * - 延迟重试（批次二 2.4）：消费异常按指数退避把消息投到 litisle.search.delay.exchange
 *   （x-delayed-message，routingKey=队列名），延迟后落回原队列；超重试次数才进 DLQ
 *
 * 【消息转换】Jackson2JsonMessageConverter 已下沉 common-mq 公共模块
 * （CommonMqAutoConfiguration 统一注册，禁 Java 原生反序列化），此处不再重复声明
 */
@Configuration
public class SearchMqConfig {

    // ==================== 业务交换机（幂等声明，发布方为 content / social） ====================

    /** 内容模块交换机（Topic，durable；幂等声明，与 content 侧声明参数一致） */
    @Bean
    public TopicExchange contentExchange() {
        return new TopicExchange(MqConstant.EXCHANGE_CONTENT, true, false);
    }

    /** 社交模块交换机（Topic，durable；幂等声明，与 social 侧声明参数一致） */
    @Bean
    public TopicExchange socialExchange() {
        return new TopicExchange(MqConstant.EXCHANGE_SOCIAL, true, false);
    }

    // ==================== 死信交换机 + 死信队列 ====================

    /** 独立死信交换机（Direct，durable）：索引同步死信链路专用，与业务交换机隔离 */
    @Bean
    public DirectExchange searchDlx() {
        return new DirectExchange(SearchBizConstant.EXCHANGE_SEARCH_DLX, true, false);
    }

    /** 索引同步死信队列（durable）：六个同步队列的死信按队列名统一汇入 */
    @Bean
    public Queue searchIndexDlq() {
        return QueueBuilder.durable(SearchBizConstant.QUEUE_SEARCH_INDEX_DLQ).build();
    }

    // ==================== 延迟重投交换机（批次二 2.4 指数退避重试） ====================

    /**
     * 索引同步延迟重投交换机（x-delayed-message，durable）：
     * 消费异常时消息先投到这里，插件按 x-delay 消息头延迟后按 routingKey（队列名）落回原队列，
     * 主队列不被失败消息长时间占用；依赖 broker 启用 rabbitmq_delayed_message_exchange 插件
     *
     * @return 延迟类型交换机（底层投递类型为 direct，与队列名精确匹配）
     */
    @Bean
    public CustomExchange searchDelayExchange() {
        // 插件要求显式声明底层交换机类型（x-delayed-type），这里用 direct 与队列名一一绑定
        Map<String, Object> args = new HashMap<>();
        args.put(SearchBizConstant.DELAYED_EXCHANGE_ARG_TYPE, SearchBizConstant.DELAYED_EXCHANGE_TYPE_DIRECT);
        return new CustomExchange(SearchBizConstant.EXCHANGE_SEARCH_DELAY,
                SearchBizConstant.EXCHANGE_TYPE_DELAYED, true, false, args);
    }

    // ==================== 六个索引同步队列（均配 DLX 兜底） ====================

    /** 文章发布事件队列（upsert article_idx + 向量预计算 + chunks 摄入） */
    @Bean
    public Queue contentPublishedQueue() {
        return indexSyncQueue(MqConstant.QUEUE_CONTENT_PUBLISHED);
    }

    /** 内容下架事件队列（删除 article_idx 文档，幂等 404 视为成功） */
    @Bean
    public Queue contentUnpublishedQueue() {
        return indexSyncQueue(MqConstant.QUEUE_CONTENT_UNPUBLISHED);
    }

    /** 文章元数据更新事件队列（partial update 元数据字段，不动向量与 chunks） */
    @Bean
    public Queue contentArticleUpdatedQueue() {
        return indexSyncQueue(MqConstant.QUEUE_CONTENT_ARTICLE_UPDATED);
    }

    /** 文章浏览量落库事件队列（覆盖更新 view_cnt 并重算 heat） */
    @Bean
    public Queue contentViewCountFlushedQueue() {
        return indexSyncQueue(MqConstant.QUEUE_CONTENT_VIEW_COUNT_FLUSHED);
    }

    /** 点赞事件队列（like_cnt 增量 + 重算 heat） */
    @Bean
    public Queue socialLikedQueue() {
        return indexSyncQueue(MqConstant.QUEUE_SOCIAL_LIKED);
    }

    /** 评论事件队列（comment_cnt 增量 + 重算 heat） */
    @Bean
    public Queue socialCommentedQueue() {
        return indexSyncQueue(MqConstant.QUEUE_SOCIAL_COMMENTED);
    }

    /**
     * 构建索引同步队列（durable + 指向死信交换机）：死信按「队列名」投递到 DLX，
     * DLQ 以六条同名绑定收全（见下方 searchIndexDlqBinding*）
     *
     * <p>死信路由键取队列名而非业务路由键：六个队列共用同一个 DLX，Direct 交换机按 routingKey
     * 精确匹配，只有路由键与绑定键完全一致才能进 DLQ；用队列名可保证各队列死信互不干扰且唯一。</p>
     *
     * @param queueName 队列名（取 MqConstant 常量，SSOT 收口）
     * @return 带死信配置的 durable 队列
     */
    private Queue indexSyncQueue(String queueName) {
        return QueueBuilder.durable(queueName)
                .deadLetterExchange(SearchBizConstant.EXCHANGE_SEARCH_DLX)
                // 死信路由键 = 队列名（DLQ 侧按同名键绑定收全，见 searchIndexDlqBinding*）
                .deadLetterRoutingKey(queueName)
                .build();
    }

    // ==================== 绑定：业务交换机 → 同步队列 ====================

    /** 绑定：content.published → 文章发布队列 */
    @Bean
    public Binding contentPublishedBinding(TopicExchange contentExchange, Queue contentPublishedQueue) {
        return BindingBuilder.bind(contentPublishedQueue).to(contentExchange)
                .with(MqConstant.ROUTING_CONTENT_PUBLISHED);
    }

    /** 绑定：content.unpublished → 内容下架队列 */
    @Bean
    public Binding contentUnpublishedBinding(TopicExchange contentExchange, Queue contentUnpublishedQueue) {
        return BindingBuilder.bind(contentUnpublishedQueue).to(contentExchange)
                .with(MqConstant.ROUTING_CONTENT_UNPUBLISHED);
    }

    /** 绑定：content.article-updated → 元数据更新队列 */
    @Bean
    public Binding contentArticleUpdatedBinding(TopicExchange contentExchange, Queue contentArticleUpdatedQueue) {
        return BindingBuilder.bind(contentArticleUpdatedQueue).to(contentExchange)
                .with(MqConstant.ROUTING_CONTENT_ARTICLE_UPDATED);
    }

    /** 绑定：content.view-count-flushed → 浏览量落库队列 */
    @Bean
    public Binding contentViewCountFlushedBinding(TopicExchange contentExchange, Queue contentViewCountFlushedQueue) {
        return BindingBuilder.bind(contentViewCountFlushedQueue).to(contentExchange)
                .with(MqConstant.ROUTING_CONTENT_VIEW_COUNT_FLUSHED);
    }

    /** 绑定：social.liked → 点赞事件队列 */
    @Bean
    public Binding socialLikedBinding(TopicExchange socialExchange, Queue socialLikedQueue) {
        return BindingBuilder.bind(socialLikedQueue).to(socialExchange)
                .with(MqConstant.ROUTING_SOCIAL_LIKED);
    }

    /** 绑定：social.commented → 评论事件队列 */
    @Bean
    public Binding socialCommentedBinding(TopicExchange socialExchange, Queue socialCommentedQueue) {
        return BindingBuilder.bind(socialCommentedQueue).to(socialExchange)
                .with(MqConstant.ROUTING_SOCIAL_COMMENTED);
    }

    // ==================== 绑定：延迟交换机 → 同步队列（批次二 2.4 指数退避重试） ====================

    /**
     * 构建「延迟交换机 → 队列」绑定（六条，绑定键 = 目标队列名）：
     * 消费端重投时以队列名作 routingKey，延迟结束后由插件按 direct 语义落回对应主队列，
     * 无需为每个队列单独建延迟交换机；新增同步队列时同步追加一条绑定即可。
     *
     * <p>CustomExchange 在 BindingBuilder 中没有专用重载，直接构造 Binding 避免类型推断歧义。</p>
     *
     * @param queue          目标队列
     * @param delayExchange  延迟交换机（x-delayed-message 类型）
     * @return 绑定键为队列名的队列绑定
     */
    private Binding delayBinding(Queue queue, CustomExchange delayExchange) {
        // 绑定键取队列名：与队列 deadLetterRoutingKey、消费端重投 routingKey 三处同值
        return new Binding(queue.getName(), Binding.DestinationType.QUEUE,
                delayExchange.getName(), queue.getName(), null);
    }

    /** 延迟重投绑定：→ 文章发布队列 */
    @Bean
    public Binding searchDelayBindingPublished(CustomExchange searchDelayExchange, Queue contentPublishedQueue) {
        return delayBinding(contentPublishedQueue, searchDelayExchange);
    }

    /** 延迟重投绑定：→ 内容下架队列 */
    @Bean
    public Binding searchDelayBindingUnpublished(CustomExchange searchDelayExchange, Queue contentUnpublishedQueue) {
        return delayBinding(contentUnpublishedQueue, searchDelayExchange);
    }

    /** 延迟重投绑定：→ 元数据更新队列 */
    @Bean
    public Binding searchDelayBindingArticleUpdated(CustomExchange searchDelayExchange, Queue contentArticleUpdatedQueue) {
        return delayBinding(contentArticleUpdatedQueue, searchDelayExchange);
    }

    /** 延迟重投绑定：→ 浏览量落库队列 */
    @Bean
    public Binding searchDelayBindingViewCountFlushed(CustomExchange searchDelayExchange, Queue contentViewCountFlushedQueue) {
        return delayBinding(contentViewCountFlushedQueue, searchDelayExchange);
    }

    /** 延迟重投绑定：→ 点赞事件队列 */
    @Bean
    public Binding searchDelayBindingLiked(CustomExchange searchDelayExchange, Queue socialLikedQueue) {
        return delayBinding(socialLikedQueue, searchDelayExchange);
    }

    /** 延迟重投绑定：→ 评论事件队列 */
    @Bean
    public Binding searchDelayBindingCommented(CustomExchange searchDelayExchange, Queue socialCommentedQueue) {
        return delayBinding(socialCommentedQueue, searchDelayExchange);
    }

    // ==================== 绑定：死信交换机 → 死信队列 ====================

    /**
     * 绑定：DLX → 死信队列（六条绑定键 = 队列名，与各队列 deadLetterRoutingKey 一致；
     * Direct 精确匹配，新增同步队列时同步追加一条绑定即可）
     */
    @Bean
    public Binding searchIndexDlqBindingPublished(DirectExchange searchDlx, Queue searchIndexDlq) {
        return BindingBuilder.bind(searchIndexDlq).to(searchDlx)
                .with(MqConstant.QUEUE_CONTENT_PUBLISHED);
    }

    /** 绑定：DLX ← 内容下架队列死信 */
    @Bean
    public Binding searchIndexDlqBindingUnpublished(DirectExchange searchDlx, Queue searchIndexDlq) {
        return BindingBuilder.bind(searchIndexDlq).to(searchDlx)
                .with(MqConstant.QUEUE_CONTENT_UNPUBLISHED);
    }

    /** 绑定：DLX ← 元数据更新队列死信 */
    @Bean
    public Binding searchIndexDlqBindingArticleUpdated(DirectExchange searchDlx, Queue searchIndexDlq) {
        return BindingBuilder.bind(searchIndexDlq).to(searchDlx)
                .with(MqConstant.QUEUE_CONTENT_ARTICLE_UPDATED);
    }

    /** 绑定：DLX ← 浏览量落库队列死信 */
    @Bean
    public Binding searchIndexDlqBindingViewCountFlushed(DirectExchange searchDlx, Queue searchIndexDlq) {
        return BindingBuilder.bind(searchIndexDlq).to(searchDlx)
                .with(MqConstant.QUEUE_CONTENT_VIEW_COUNT_FLUSHED);
    }

    /** 绑定：DLX ← 点赞事件队列死信 */
    @Bean
    public Binding searchIndexDlqBindingLiked(DirectExchange searchDlx, Queue searchIndexDlq) {
        return BindingBuilder.bind(searchIndexDlq).to(searchDlx)
                .with(MqConstant.QUEUE_SOCIAL_LIKED);
    }

    /** 绑定：DLX ← 评论事件队列死信 */
    @Bean
    public Binding searchIndexDlqBindingCommented(DirectExchange searchDlx, Queue searchIndexDlq) {
        return BindingBuilder.bind(searchIndexDlq).to(searchDlx)
                .with(MqConstant.QUEUE_SOCIAL_COMMENTED);
    }

    // 事件体 JSON 序列化（Jackson2JsonMessageConverter）已由 common-mq 统一注册，
    // 避免与 CommonMqAutoConfiguration 的 @ConditionalOnMissingBean(MessageConverter) 重复声明
}
