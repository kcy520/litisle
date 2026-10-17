package com.litisle.litislecontent.config;

import com.litisle.litisleapi.constant.MqConstant;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * content 侧审核结果回写 MQ 拓扑声明（板块三 3-5，§4.5.2）。
 *
 * 【拓扑】content 消费两路结果：
 * - moderation.text.result → 审核/配图复审结果队列（文章双链路聚合回写 article 状态）
 * - moderation.appeal.result → 申诉改判结果队列（驳回升发布 3→2 + last-hash 失效）
 *
 * 【声明幂等】交换机（业务 Topic / 独立 Direct DLX / x-delayed 延迟交换机）均由 review 侧
 * ReviewRabbitConfig 先声明，此处重复声明幂等生效——但参数必须完全一致，否则
 * PRECONDITION_FAILED（Topic/durable/autoDelete 与 x-delayed-type=direct 三处对齐）
 *
 * 【消息转换】Jackson2JsonMessageConverter 已下沉 common-mq 统一注册，此处不再声明
 *
 * 【DLX 纪律】主队列挂独立 Direct DLX（litisle.moderation.dlx），消费失败 nack requeue=false
 * 经 DLX 进本侧 DLQ 兜底；DLQ 无 TTL、无二级 DLX（防死信再死信成环）
 *
 * @author litisle
 */
@Configuration
public class ContentRabbitConfig {

    // ==================== 交换机（声明幂等，参数与 review 侧 ReviewRabbitConfig 严格对齐） ====================

    /**
     * 审核业务交换机（Topic，与 review 侧同参幂等声明：绑定前必须存在）
     */
    @Bean
    public TopicExchange moderationExchange() {
        return new TopicExchange(MqConstant.EXCHANGE_MODERATION, true, false);
    }

    /**
     * 审核死信交换机（独立 Direct DLX，与业务交换机解耦：Direct 精确匹配无通配语义，
     * 杜绝业务侧通配绑定误吸死信）
     */
    @Bean
    public DirectExchange moderationDlx() {
        return new DirectExchange(MqConstant.EXCHANGE_MODERATION_DLX, true, false);
    }

    /**
     * 审核延迟重试交换机（x-delayed-message 插件类型，消费端指数退避延迟重投落回本侧主队列；
     * 前提 broker 启用 rabbitmq_delayed_message_exchange 插件，未启用启动报错 fail-fast）
     */
    @Bean
    public CustomExchange moderationDelayExchange() {
        return new CustomExchange(
                MqConstant.EXCHANGE_MODERATION_DELAY,  // 交换机名称
                "x-delayed-message",                   // 插件类型：按 x-delay 消息头延迟投递
                true,                                  // durable：持久化
                false,                                 // autoDelete：不自动删除
                Map.of("x-delayed-type", "direct"));   // 延迟后按原路由键精确投递（与 review 侧同参）
    }

    // ==================== 审核结果主链路（队列 + 三向绑定） ====================

    /**
     * 审核/配图复审结果主队列（durable + DLX 兜底）
     *
     * 【作用】接收 moderation.text.result 消息（bizType=1 文章文本 result 与配图复审 result），
     * 由 {@code ModerationResultConsumer} 消费做双链路聚合 + CAS 回写 article 状态 + last-hash 写入
     */
    @Bean
    public Queue contentModerationTextResultQueue() {
        return QueueBuilder
                .durable(MqConstant.QUEUE_CONTENT_MODERATION_TEXT_RESULT)         // 持久化业务队列
                .deadLetterExchange(MqConstant.EXCHANGE_MODERATION_DLX)           // 绑定死信交换机
                .deadLetterRoutingKey(MqConstant.ROUTING_MODERATION_TEXT_RESULT_DLQ) // 死信路由键
                .build();
    }

    /** 审核结果死信队列（durable；无 TTL、无二级 DLX，防死信再死信成环） */
    @Bean
    public Queue contentModerationTextResultDlq() {
        return QueueBuilder.durable(MqConstant.QUEUE_CONTENT_MODERATION_TEXT_RESULT_DLQ).build();
    }

    /** 主队列 ↔ 业务 Topic 交换机绑定（RoutingKey=moderation.text.result 精确订阅） */
    @Bean
    public Binding contentModerationTextResultBinding(
            TopicExchange moderationExchange,
            Queue contentModerationTextResultQueue) {
        return BindingBuilder
                .bind(contentModerationTextResultQueue)         // 绑定主队列
                .to(moderationExchange)                         // 到业务 Topic 交换机
                .with(MqConstant.ROUTING_MODERATION_TEXT_RESULT); // 精确路由键
    }

    /**
     * 主队列 ↔ 延迟交换机绑定（消费失败延迟重投的消息按原路由键落回本主队列；
     * 同一队列绑定业务/延迟两个交换机属正常拓扑，不触发参数冲突）
     */
    @Bean
    public Binding contentModerationTextResultDelayBinding(
            CustomExchange moderationDelayExchange,
            Queue contentModerationTextResultQueue) {
        return BindingBuilder
                .bind(contentModerationTextResultQueue)         // 绑定主队列
                .to(moderationDelayExchange)                    // 到延迟重试专用交换机
                .with(MqConstant.ROUTING_MODERATION_TEXT_RESULT) // 复用原路由键
                .noargs(); // spring-amqp 3.2 起 with(String) 返回 GenericArgumentsConfigurer，需 noargs() 收尾
    }

    /** 死信队列 ↔ 死信 Direct 交换机绑定（精确死信路由键一对一投递，防通配误吸） */
    @Bean
    public Binding contentModerationTextResultDlqBinding(
            DirectExchange moderationDlx,
            Queue contentModerationTextResultDlq) {
        return BindingBuilder
                .bind(contentModerationTextResultDlq)           // 绑定死信队列
                .to(moderationDlx)                              // 到死信 Direct 交换机
                .with(MqConstant.ROUTING_MODERATION_TEXT_RESULT_DLQ); // 死信路由键
    }

    // ==================== 申诉改判链路（队列 + 三向绑定，同主链路口径） ====================

    /**
     * 申诉改判结果主队列（durable + DLX 兜底）
     *
     * 【作用】接收 moderation.appeal.result 消息（bizType=1 文章），改判 PASS 时
     * CAS 3→2 恢复发布 + 删 last-hash 免审键（5-4.5：平台结论反转以最新为准）
     */
    @Bean
    public Queue contentModerationAppealResultQueue() {
        return QueueBuilder
                .durable(MqConstant.QUEUE_CONTENT_MODERATION_APPEAL_RESULT)
                .deadLetterExchange(MqConstant.EXCHANGE_MODERATION_DLX)
                .deadLetterRoutingKey(MqConstant.ROUTING_MODERATION_APPEAL_RESULT_DLQ)
                .build();
    }

    /** 申诉改判死信队列（durable；无 TTL、无二级 DLX） */
    @Bean
    public Queue contentModerationAppealResultDlq() {
        return QueueBuilder.durable(MqConstant.QUEUE_CONTENT_MODERATION_APPEAL_RESULT_DLQ).build();
    }

    /** 主队列 ↔ 业务 Topic 交换机绑定（RoutingKey=moderation.appeal.result 精确订阅） */
    @Bean
    public Binding contentModerationAppealResultBinding(
            TopicExchange moderationExchange,
            Queue contentModerationAppealResultQueue) {
        return BindingBuilder
                .bind(contentModerationAppealResultQueue)
                .to(moderationExchange)
                .with(MqConstant.ROUTING_MODERATION_APPEAL_RESULT);
    }

    /** 主队列 ↔ 延迟交换机绑定（消费失败延迟重投按原路由键落回） */
    @Bean
    public Binding contentModerationAppealResultDelayBinding(
            CustomExchange moderationDelayExchange,
            Queue contentModerationAppealResultQueue) {
        return BindingBuilder
                .bind(contentModerationAppealResultQueue)
                .to(moderationDelayExchange)
                .with(MqConstant.ROUTING_MODERATION_APPEAL_RESULT)
                .noargs(); // spring-amqp 3.2 起 with(String) 需 noargs() 收尾才返回 Binding
    }

    /** 死信队列 ↔ 死信 Direct 交换机绑定 */
    @Bean
    public Binding contentModerationAppealResultDlqBinding(
            DirectExchange moderationDlx,
            Queue contentModerationAppealResultDlq) {
        return BindingBuilder
                .bind(contentModerationAppealResultDlq)
                .to(moderationDlx)
                .with(MqConstant.ROUTING_MODERATION_APPEAL_RESULT_DLQ);
    }

    // ==================== 封禁连带链路（板块八 8.2，队列 + 三向绑定） ====================

    /**
     * 用户封禁业务交换机（Topic，幂等声明：user 侧发布方同参声明，参数必须一致防 PRECONDITION_FAILED）
     */
    @Bean
    public TopicExchange userExchange() {
        return new TopicExchange(MqConstant.EXCHANGE_USER, true, false);
    }

    /**
     * 封禁连带下架主队列（durable + DLX 兑底）
     *
     * 【作用】接收 user.banned 消息，由 UserBannedConsumer 消费做作者全部已发布文章
     * 批量置 4-下架 + 逐篇发 content.unpublished + 缓存三键失效
     */
    @Bean
    public Queue contentUserBannedQueue() {
        return QueueBuilder
                .durable(MqConstant.QUEUE_CONTENT_USER_BANNED)
                .deadLetterExchange(MqConstant.EXCHANGE_MODERATION_DLX)
                .deadLetterRoutingKey(MqConstant.ROUTING_USER_BANNED_DLQ)
                .build();
    }

    /** 封禁连带死信队列（durable；无 TTL、无二级 DLX，防死信再死信成环） */
    @Bean
    public Queue contentUserBannedDlq() {
        return QueueBuilder.durable(MqConstant.QUEUE_CONTENT_USER_BANNED_DLQ).build();
    }

    /** 主队列 ↔ 用户 Topic 交换机绑定（RoutingKey=user.banned 精确订阅） */
    @Bean
    public Binding contentUserBannedBinding(TopicExchange userExchange,
                                            Queue contentUserBannedQueue) {
        return BindingBuilder
                .bind(contentUserBannedQueue)
                .to(userExchange)
                .with(MqConstant.ROUTING_USER_BANNED);
    }

    /** 主队列 ↔ 延迟交换机绑定（消费失败延迟重投按原路由键落回；复用 moderation 延迟交换机） */
    @Bean
    public Binding contentUserBannedDelayBinding(CustomExchange moderationDelayExchange,
                                                 Queue contentUserBannedQueue) {
        return BindingBuilder
                .bind(contentUserBannedQueue)
                .to(moderationDelayExchange)
                .with(MqConstant.ROUTING_USER_BANNED)
                .noargs(); // spring-amqp 3.2 起 with(String) 需 noargs() 收尾才返回 Binding
    }

    /** 死信队列 ↔ 死信 Direct 交换机绑定（精确死信路由键一对一投递，防通配误吸） */
    @Bean
    public Binding contentUserBannedDlqBinding(DirectExchange moderationDlx,
                                               Queue contentUserBannedDlq) {
        return BindingBuilder
                .bind(contentUserBannedDlq)
                .to(moderationDlx)
                .with(MqConstant.ROUTING_USER_BANNED_DLQ);
    }
}