package com.litisle.litislereview.moderation.config;

import com.litisle.litisleapi.constant.MqConstant;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 审核模块 MQ 拓扑声明（review 侧消费端）。
 *
 * 【拓扑】
 * - Exchange：litisle.moderation.exchange（Topic，durable；发布方发布，声明幂等）
 * - 文本送审队列：durable + DLX 兜底（消费失败 nack requeue=false 经 DLX 进死信队列，防毒丸阻塞主队列）
 * - DLX 为独立 Direct 交换机（litisle.moderation.dlx），与业务交换机解耦：
 *   Direct 精确匹配无通配语义，杜绝业务侧通配绑定（如 moderation.#）误吸死信
 *
 * 【消息转换】Jackson2JsonMessageConverter 已下沉 common-mq 公共模块
 * （CommonMqAutoConfiguration 统一注册，禁 Java 原生反序列化），此处不再重复声明
 *
 * 【参数对齐】交换机/队列参数须与 file 侧 ModerationMqConfig 一致，否则 PRECONDITION_FAILED
 */
@Configuration
public class ReviewRabbitConfig {

    @Bean
    public TopicExchange moderationExchange() {
        return new TopicExchange(MqConstant.EXCHANGE_MODERATION, true, false);
    }

    /** 独立死信交换机（Direct，durable）：死信链路专用，与业务交换机隔离 */
    @Bean
    public DirectExchange moderationDlx() {
        return new DirectExchange(MqConstant.EXCHANGE_MODERATION_DLX, true, false);
    }

    @Bean
    public Queue moderationTextSubmitQueue() {
        return QueueBuilder.durable(MqConstant.QUEUE_MODERATION_TEXT_SUBMIT)
                .deadLetterExchange(MqConstant.EXCHANGE_MODERATION_DLX)
                .deadLetterRoutingKey(MqConstant.ROUTING_MODERATION_TEXT_SUBMIT_DLQ)
                .build();
    }

    @Bean
    public Queue moderationTextSubmitDlq() {
        return QueueBuilder.durable(MqConstant.QUEUE_MODERATION_TEXT_SUBMIT_DLQ).build();
    }

    @Bean
    public Binding moderationTextSubmitBinding(TopicExchange moderationExchange,
                                               Queue moderationTextSubmitQueue) {
        return BindingBuilder.bind(moderationTextSubmitQueue)
                .to(moderationExchange)
                .with(MqConstant.ROUTING_MODERATION_TEXT_SUBMIT);
    }

    @Bean
    public Binding moderationTextSubmitDlqBinding(DirectExchange moderationDlx,
                                                  Queue moderationTextSubmitDlq) {
        return BindingBuilder.bind(moderationTextSubmitDlq)
                .to(moderationDlx)
                .with(MqConstant.ROUTING_MODERATION_TEXT_SUBMIT_DLQ);
    }
}
