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


    /**
     * 独立死信交换机（Dead Letter Exchange，简称 DLX）
     *
     * 【作用】
     * 专门接收「主队列中消费失败（reject/nack 且 requeue=false）」的消息，
     * 与业务交换机（TopicExchange）完全解耦，避免死信污染正常业务链路。
     *
     * 【为什么用 Direct 而不是 Topic】
     * - Direct：精确路由，无通配符
     * - 防止业务侧误配置（如 moderation.#）把死信又路由回业务队列
     * - 死信场景只需要「一对一」投递，不需要灵活匹配
     *
     * 【参数说明】
     * - name：交换机名称（来自 MqConstant）
     * - durable=true：RabbitMQ 重启后交换机不丢失
     * - autoDelete=false：无消费者时也不自动删除
     */
    @Bean
    public DirectExchange moderationDlx() {
        return new DirectExchange(
                MqConstant.EXCHANGE_MODERATION_DLX, // 交换机名称
                true,  // durable：持久化
                false  // autoDelete：不自动删除
        );
    }

    /**
     * 文本送审主队列（业务队列）
     *
     * 【作用】
     * 接收来自业务服务（如文章/评论发布）的文本审核请求消息，
     * 由审核模块的 Listener 消费并执行机审（DFA / LLM / 图片模型）。
     *
     * 【队列参数】
     * - durable：持久化，RabbitMQ 重启后队列不丢失
     * - deadLetterExchange：指定死信交换机（DLX）
     * - deadLetterRoutingKey：死信路由键（决定消息进哪个死信队列）
     *
     * 【消费失败处理】
     * Listener 中消费异常时：
     * - nack(requeue=false)
     * → 消息被转发到 moderationDlx
     * → 再路由到 moderationTextSubmitDlq
     * → 主队列不被阻塞（防「毒丸消息」）
     */
    @Bean
    public Queue moderationTextSubmitQueue() {
        return QueueBuilder
                .durable(MqConstant.QUEUE_MODERATION_TEXT_SUBMIT) // 持久化业务队列
                .deadLetterExchange(MqConstant.EXCHANGE_MODERATION_DLX) // 绑定死信交换机
                .deadLetterRoutingKey(MqConstant.ROUTING_MODERATION_TEXT_SUBMIT_DLQ) // 死信路由键
                .build();
    }

    /**
     * 文本送审死信队列（DLQ，Dead Letter Queue）
     *
     * 【作用】
     * 存放「消费失败且不允许重新入队」的消息（即毒丸消息）：
     * - 机审服务异常
     * - 消息格式错误
     * - 连续 N 次处理失败
     *
     * 【运维价值】
     * - 不丢消息（可追溯）
     * - 不阻塞主队列
     * - 可人工排查 / 告警 / 定时重试
     *
     * 【配置说明】
     * - durable：持久化
     * - 无 TTL：死信可长期保留
     * - 无 DLX：避免死信再死信形成环路
     */
    @Bean
    public Queue moderationTextSubmitDlq() {
        return QueueBuilder
                .durable(MqConstant.QUEUE_MODERATION_TEXT_SUBMIT_DLQ) // 持久化死信队列
                .build();
    }

    /**
     * 文本送审队列 ↔ 业务交换机绑定
     *
     * 【作用】
     * 将主队列绑定到 Topic 类型的业务交换机，
     * 通过 RoutingKey 精确订阅文本送审类消息。
     *
     * 【路由示例】
     * - RoutingKey：moderation.text.submit
     * - 只有匹配该 key 的消息才会进入 moderationTextSubmitQueue
     *
     * 【参数说明】
     * - TopicExchange：支持灵活路由（但此处用精确 key）
     * - with(routingKey)：绑定键
     */
    @Bean
    public Binding moderationTextSubmitBinding(
            TopicExchange moderationExchange,
            Queue moderationTextSubmitQueue) {
        return BindingBuilder
                .bind(moderationTextSubmitQueue)        // 绑定主队列
                .to(moderationExchange)                 // 到业务 Topic 交换机
                .with(MqConstant.ROUTING_MODERATION_TEXT_SUBMIT); // 精确路由键
    }

    /**
     * 死信队列 ↔ 死信交换机绑定
     *
     * 【作用】
     * 将死信队列绑定到独立的 Direct 死信交换机，
     * 使用「死信专用 RoutingKey」完成精确路由。
     *
     * 【为什么是 Direct + 精确 key】
     * - 死信路由不需要通配
     * - 防止业务通配规则（如 #）误吸死信
     * - 一对一关系更清晰、可审计
     *
     * 【完整死信链路】
     * 消费失败
     * → nack(requeue=false)
     * → 主队列 DLX
     * → DirectExchange
     * → RoutingKey 匹配
     * → 死信队列
     */
    @Bean
    public Binding moderationTextSubmitDlqBinding(
            DirectExchange moderationDlx,
            Queue moderationTextSubmitDlq) {
        return BindingBuilder
                .bind(moderationTextSubmitDlq)          // 绑定死信队列
                .to(moderationDlx)                      // 到死信 Direct 交换机
                .with(MqConstant.ROUTING_MODERATION_TEXT_SUBMIT_DLQ); // 死信路由键
    }
}
