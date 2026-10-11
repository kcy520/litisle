package com.litisle.litisleauth.mq;


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
 * 处罚结果消费拓扑配置（临时清单 B1，§4.2.3/§4.5.2）
 *
 * 【拓扑说明】
 * - 主交换机 EXCHANGE_MODERATION / 死信交换机 EXCHANGE_MODERATION_DLX 为双侧共用，
 *   同名同参声明幂等（Spring AMQP 对已存在同参资源不报错；参数不一致报 PRECONDITION_FAILED，
 *   故本类参数必须与 review 侧 ReviewRabbitConfig 精确对齐）
 * - 延迟交换机 EXCHANGE_MODERATION_DELAY（x-delayed-message 插件）本类【不声明】：
 *   其 arguments 必须与 review 侧声明完全一致，重复声明徒增 PRECONDITION_FAILED 风险面；
 *   auth 侧仅作为发送方使用（消费端指数退避重投），交换机由 review 侧启动时声明
 * - 主队列绑定 ROUTING_MODERATION_PUNISH；DLX/DLQ 参数与 MqConstant 注释口径一致
 *
 * @author litisle
 */
@Configuration
public class PunishMqConfig {

    /**
     * 处罚结果主交换机（与 review 侧共用，同名同参幂等声明）
     */
    @Bean
    public TopicExchange moderationExchange() {
        return new TopicExchange(MqConstant.EXCHANGE_MODERATION, true, false);
    }

    /**
     * 审核死信交换机（与 review 侧共用，消费失败 nack(requeue=false) 后经此路由入 DLQ）
     */
    @Bean
    public DirectExchange moderationDlx() {
        return new DirectExchange(MqConstant.EXCHANGE_MODERATION_DLX, true, false);
    }

    /**
     * auth 侧处罚结果主队列：durable + DLX 死信参数（消费耗尽重试后经 DLX 入 DLQ，防毒消息无限重投）
     */
    @Bean
    public Queue authPunishQueue() {
        return QueueBuilder.durable(MqConstant.QUEUE_AUTH_MODERATION_PUNISH)
                .deadLetterExchange(MqConstant.EXCHANGE_MODERATION_DLX)
                .deadLetterRoutingKey(MqConstant.ROUTING_MODERATION_PUNISH_DLQ)
                .build();
    }

    /**
     * auth 侧处罚结果死信队列：留痕兜底，由双向对账 sweeper（5-3.2）扫描补漏
     */
    @Bean
    public Queue authPunishDlqQueue() {
        return QueueBuilder.durable(MqConstant.QUEUE_AUTH_MODERATION_PUNISH_DLQ).build();
    }

    /**
     * 主队列 ← 主交换机绑定（routingKey=moderation.punish.result，与 review 侧 5-2.1 发布键一致）
     */
    @Bean
    public Binding authPunishBinding(TopicExchange moderationExchange, Queue authPunishQueue) {
        return BindingBuilder.bind(authPunishQueue).to(moderationExchange)
                .with(MqConstant.ROUTING_MODERATION_PUNISH);
    }

    /**
     * DLQ ← DLX 绑定（与主队列 x-dead-letter-routing-key 一致，死信精确落位）
     */
    @Bean
    public Binding authPunishDlqBinding(DirectExchange moderationDlx, Queue authPunishDlqQueue) {
        return BindingBuilder.bind(authPunishDlqQueue).to(moderationDlx)
                .with(MqConstant.ROUTING_MODERATION_PUNISH_DLQ);
    }

}
