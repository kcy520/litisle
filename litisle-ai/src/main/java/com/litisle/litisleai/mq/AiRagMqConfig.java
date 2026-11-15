package com.litisle.litisleai.mq;

import com.litisle.litisleapi.constant.MqConstant;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * AI RAG 摄入 MQ 拓扑声明（板块四 4.1：AI 侧独立消费队列，绑定 content 交换机既有路由键）
 *
 * <p>【拓扑口径】published/unpublished 路由键为 content 既有事件（P8 定档：无需新增契约）；
 * AI 用独立队列绑定同一路由键（Topic 交换机扇出到多队列，与 search 的消费队列互不抢占）；
 * DLX 为 Direct 类型（新建 litisle.ai.rag.dlx，防 Topic 通配误吸）；
 * 延迟重试交换机为 x-delayed-message 插件类型（与 search/moderation 的延迟链路同构）。</p>
 *
 * <p>【条件装配】rag.enabled=false 时本类全部 Bean 缺席（本地无 MQ 消费需求不声明拓扑）。</p>
 *
 * @author litisle
 */
@Configuration
@ConditionalOnProperty(prefix = "litisle.ai.rag", name = "enabled", havingValue = "true")
public class AiRagMqConfig {

    /** 死信交换机名 */
    public static final String EXCHANGE_AI_RAG_DLX = "litisle.ai.rag.dlx";

    /**
     * 文章发布摄入队列（绑定 EXCHANGE_CONTENT 的 content.published；含 DLX 参数与死信路由键）
     *
     * @return 队列声明
     */
    @Bean
    public Queue aiRagPublishedQueue() {
        return QueueBuilder.durable(MqConstant.QUEUE_AI_RAG_PUBLISHED)
                .deadLetterExchange(EXCHANGE_AI_RAG_DLX)
                .deadLetterRoutingKey(MqConstant.ROUTING_AI_RAG_DLQ)
                .build();
    }

    /**
     * 文章发布摄入死信队列（消费重试耗尽兜底）
     *
     * @return 队列声明
     */
    @Bean
    public Queue aiRagPublishedDlq() {
        return QueueBuilder.durable(MqConstant.QUEUE_AI_RAG_PUBLISHED_DLQ).build();
    }

    /**
     * 内容下架摄入队列（绑定 EXCHANGE_CONTENT 的 content.unpublished；含 DLX 参数）
     *
     * @return 队列声明
     */
    @Bean
    public Queue aiRagUnpublishedQueue() {
        return QueueBuilder.durable(MqConstant.QUEUE_AI_RAG_UNPUBLISHED)
                .deadLetterExchange(EXCHANGE_AI_RAG_DLX)
                .deadLetterRoutingKey(MqConstant.ROUTING_AI_RAG_DLQ)
                .build();
    }

    /**
     * 内容下架死信队列（消费重试耗尽兜底）
     *
     * @return 队列声明
     */
    @Bean
    public Queue aiRagUnpublishedDlq() {
        return QueueBuilder.durable(MqConstant.QUEUE_AI_RAG_UNPUBLISHED_DLQ).build();
    }

    /**
     * 摄入死信交换机（Direct 类型：与业务交换机隔离，防 Topic 通配绑定误吸死信）
     *
     * @return 死信交换机
     */
    @Bean
    public DirectExchange aiRagDlx() {
        return new DirectExchange(EXCHANGE_AI_RAG_DLX);
    }

    /**
     * 发布死信绑定（dlx → 发布 DLQ，统一死信路由键投递）
     *
     * @param aiRagPublishedDlq 发布死信队列
     * @param aiRagDlx          死信交换机
     * @return 绑定
     */
    @Bean
    public org.springframework.amqp.core.Binding aiRagPublishedDlqBinding(Queue aiRagPublishedDlq, DirectExchange aiRagDlx) {
        return org.springframework.amqp.core.BindingBuilder.bind(aiRagPublishedDlq)
                .to(aiRagDlx).with(MqConstant.ROUTING_AI_RAG_DLQ);
    }

    /**
     * 下架死信绑定（dlx → 下架 DLQ）
     *
     * @param aiRagUnpublishedDlq 下架死信队列
     * @param aiRagDlx            死信交换机
     * @return 绑定
     */
    @Bean
    public org.springframework.amqp.core.Binding aiRagUnpublishedDlqBinding(Queue aiRagUnpublishedDlq, DirectExchange aiRagDlx) {
        return org.springframework.amqp.core.BindingBuilder.bind(aiRagUnpublishedDlq)
                .to(aiRagDlx).with(MqConstant.ROUTING_AI_RAG_DLQ);
    }

    /**
     * RAG 摄入延迟重试交换机（x-delayed-message 插件类型；重投路由键=源队列名，延迟后落回原队列）
     *
     * @return 延迟交换机
     */
    @Bean
    public CustomExchange aiRagDelayExchange() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-delayed-type", "direct");
        return new CustomExchange(MqConstant.EXCHANGE_AI_RAG_DELAY, "x-delayed-message",
                true, false, args);
    }

    /**
     * 延迟重试绑定（延迟交换机 → 发布摄入队列，路由键=队列名）
     *
     * @param aiRagPublishedQueue 发布摄入队列
     * @param aiRagDelayExchange  延迟交换机
     * @return 绑定
     */
    @Bean
    public org.springframework.amqp.core.Binding aiRagPublishedDelayBinding(Queue aiRagPublishedQueue, CustomExchange aiRagDelayExchange) {
        return org.springframework.amqp.core.BindingBuilder.bind(aiRagPublishedQueue)
                .to(aiRagDelayExchange).with(MqConstant.QUEUE_AI_RAG_PUBLISHED).noargs();
    }

    /**
     * 延迟重试绑定（延迟交换机 → 下架摄入队列）
     *
     * @param aiRagUnpublishedQueue 下架摄入队列
     * @param aiRagDelayExchange    延迟交换机
     * @return 绑定
     */
    @Bean
    public org.springframework.amqp.core.Binding aiRagUnpublishedDelayBinding(Queue aiRagUnpublishedQueue, CustomExchange aiRagDelayExchange) {
        return org.springframework.amqp.core.BindingBuilder.bind(aiRagUnpublishedQueue)
                .to(aiRagDelayExchange).with(MqConstant.QUEUE_AI_RAG_UNPUBLISHED).noargs();
    }

    /**
     * 发布事件源绑定（content 交换机为既有 Topic 交换机由 content 侧声明，AI 侧只补本队列绑定）
     *
     * @param aiRagPublishedQueue 发布摄入队列
     * @return 绑定
     */
    @Bean
    public org.springframework.amqp.core.Binding aiRagPublishedSourceBinding(Queue aiRagPublishedQueue) {
        return org.springframework.amqp.core.BindingBuilder.bind(aiRagPublishedQueue)
                .to(new org.springframework.amqp.core.TopicExchange(MqConstant.EXCHANGE_CONTENT))
                .with(MqConstant.ROUTING_CONTENT_PUBLISHED);
    }

    /**
     * 下架事件源绑定（同上）
     *
     * @param aiRagUnpublishedQueue 下架摄入队列
     * @return 绑定
     */
    @Bean
    public org.springframework.amqp.core.Binding aiRagUnpublishedSourceBinding(Queue aiRagUnpublishedQueue) {
        return org.springframework.amqp.core.BindingBuilder.bind(aiRagUnpublishedQueue)
                .to(new org.springframework.amqp.core.TopicExchange(MqConstant.EXCHANGE_CONTENT))
                .with(MqConstant.ROUTING_CONTENT_UNPUBLISHED);
    }
}
