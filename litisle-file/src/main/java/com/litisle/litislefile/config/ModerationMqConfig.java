package com.litisle.litislefile.config;


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
 * 审核模块 MQ 拓扑声明（file 侧消费端）。
 *
 * 【拓扑】
 * - Exchange：litisle.moderation.exchange（Topic，durable；review 侧发布，声明幂等）
 * - 队列：file 侧审核结果队列 + 申诉裁决结果队列，各配 DLQ 兜底
 *   （消费失败 nack requeue=false 后经 DLX 路由进死信队列，防毒丸消息阻塞主队列）
 * - DLX 为独立 Direct 交换机（litisle.moderation.dlx），与业务交换机解耦：
 *   Direct 精确匹配无通配语义，杜绝业务侧通配绑定（如 moderation.#）误吸死信，
 *   且死信链路可独立监控/治理，不随业务交换机变更受影响
 *
 * 【消息转换】Jackson2JsonMessageConverter 已下沉 common-mq 公共模块
 * （CommonMqAutoConfiguration 统一注册，禁 Java 原生反序列化），此处不再重复声明
 */
@Configuration
public class ModerationMqConfig {

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
    public Queue fileModerationResultQueue() {
        return QueueBuilder.durable(MqConstant.QUEUE_FILE_MODERATION_RESULT)
                .deadLetterExchange(MqConstant.EXCHANGE_MODERATION_DLX)
                .deadLetterRoutingKey(MqConstant.ROUTING_MODERATION_RESULT_DLQ)
                .build();
    }

    @Bean
    public Queue fileModerationResultDlq() {
        return QueueBuilder.durable(MqConstant.QUEUE_FILE_MODERATION_RESULT_DLQ).build();
    }

    @Bean
    public Queue fileModerationAppealResultQueue() {
        return QueueBuilder.durable(MqConstant.QUEUE_FILE_MODERATION_APPEAL_RESULT)
                .deadLetterExchange(MqConstant.EXCHANGE_MODERATION_DLX)
                .deadLetterRoutingKey(MqConstant.ROUTING_MODERATION_APPEAL_RESULT_DLQ)
                .build();
    }

    @Bean
    public Queue fileModerationAppealResultDlq() {
        return QueueBuilder.durable(MqConstant.QUEUE_FILE_MODERATION_APPEAL_RESULT_DLQ).build();
    }

    @Bean
    public Binding fileModerationResultBinding(TopicExchange moderationExchange,
                                               Queue fileModerationResultQueue) {
        return BindingBuilder.bind(fileModerationResultQueue)
                .to(moderationExchange)
                .with(MqConstant.ROUTING_MODERATION_RESULT);
    }

    @Bean
    public Binding fileModerationAppealResultBinding(TopicExchange moderationExchange,
                                                     Queue fileModerationAppealResultQueue) {
        return BindingBuilder.bind(fileModerationAppealResultQueue)
                .to(moderationExchange)
                .with(MqConstant.ROUTING_MODERATION_APPEAL_RESULT);
    }

    @Bean
    public Binding fileModerationResultDlqBinding(DirectExchange moderationDlx,
                                                  Queue fileModerationResultDlq) {
        return BindingBuilder.bind(fileModerationResultDlq)
                .to(moderationDlx)
                .with(MqConstant.ROUTING_MODERATION_RESULT_DLQ);
    }

    @Bean
    public Binding fileModerationAppealResultDlqBinding(DirectExchange moderationDlx,
                                                        Queue fileModerationAppealResultDlq) {
        return BindingBuilder.bind(fileModerationAppealResultDlq)
                .to(moderationDlx)
                .with(MqConstant.ROUTING_MODERATION_APPEAL_RESULT_DLQ);
    }

    // 事件体 JSON 序列化（Jackson2JsonMessageConverter）已由 common-mq 统一注册，
    // 避免与 CommonMqAutoConfiguration 的 @ConditionalOnMissingBean(MessageConverter) 重复声明
}