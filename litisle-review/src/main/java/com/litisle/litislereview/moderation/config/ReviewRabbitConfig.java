package com.litisle.litislereview.moderation.config;

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
     * 审核延迟重试专用交换机（板块三 3-8.1，x-delayed-message 插件类型）。
     *
     * 【为什么独立新建】现有 moderationExchange 为双侧共用普通 TopicExchange（file 侧已按该类型
     * 声明绑定），直接改造类型必触发 PRECONDITION_FAILED；独立新建零波及现有拓扑。
     *
     * 【x-delayed-type=direct】插件按消息 x-delay 头延迟后，再以 direct 语义按原路由键精确投递，
     * 重投复用原送审路由键落回原主队列，无需新增队列/路由键（拓扑见 MqConstant.EXCHANGE_MODERATION_DELAY 注释）。
     *
     * 【前提】broker 需启用 rabbitmq_delayed_message_exchange 插件，未启用时本声明启动报错（fail-fast）。
     */
    @Bean
    public CustomExchange moderationDelayExchange() {
        return new CustomExchange(
                MqConstant.EXCHANGE_MODERATION_DELAY, // 交换机名称
                "x-delayed-message",                  // 插件类型：按 x-delay 消息头延迟投递
                true,                                 // durable：持久化
                false,                                // autoDelete：不自动删除
                Map.of("x-delayed-type", "direct") // 插件延迟后的真实路由语义（精确匹配原路由键）
        );
    }

    /**
     * 文本送审主队列绑定延迟交换机（板块三 3-8.1）。
     *
     * 【作用】消费失败延迟重投的消息经延迟交换机按原路由键 moderation.text.submit 落回本主队列；
     * 同一队列绑定业务/延迟两个交换机属正常拓扑，不触发参数冲突。
     */
    @Bean
    public Binding moderationTextSubmitDelayBinding(
            CustomExchange moderationDelayExchange,
            Queue moderationTextSubmitQueue) {
        return BindingBuilder
                .bind(moderationTextSubmitQueue)      // 绑定文本送审主队列
                .to(moderationDelayExchange)          // 到延迟重试专用交换机
                .with(MqConstant.ROUTING_MODERATION_TEXT_SUBMIT) // 复用原送审路由键
                .noargs(); // spring-amqp 3.2 起 with(String) 返回 GenericArgumentsConfigurer，需 noargs() 收尾才返回 Binding
    }

    /**
     * 图片送审主队列绑定延迟交换机（板块三 3-8.1，同文本侧口径）。
     * 
     */
    @Bean
    public Binding moderationImageSubmitDelayBinding(
            CustomExchange moderationDelayExchange,
            Queue moderationImageSubmitQueue) {
        return BindingBuilder
                .bind(moderationImageSubmitQueue)     // 绑定图片送审主队列
                .to(moderationDelayExchange)          // 到延迟重试专用交换机
                .with(MqConstant.ROUTING_MODERATION_IMAGE_SUBMIT) // 复用原送审路由键
                .noargs(); // spring-amqp 3.2 起 with(String) 返回 GenericArgumentsConfigurer，需 noargs() 收尾才返回 Binding
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

    /**
     * 图片送审主队列（业务队列，板块三 3-2 / §4.2.2）。
     *
     * 【作用】接收 file 上传预审发布的 moderation.image.submit 消息（bizType=6，bizId=fileId），
     * 由 {@code ModerationImageConsumer} 消费后委托 {@code ModerationImageService} 走
     * FileClient.getContent 字节流取图机审（严禁按用户 URL 抓取）。
     *
     * 【参数对齐】durable / deadLetterExchange / deadLetterRoutingKey 须与 file 侧 ModerationMqConfig
     * 完全一致，否则声明冲突报 PRECONDITION_FAILED（file 侧先声明，review 侧声明幂等）。
     *
     * 【消费失败】Listener 异常 → nack(requeue=false) → moderationDlx → 图片 DLQ，主队列不被毒丸阻塞。
     */
    @Bean
    public Queue moderationImageSubmitQueue() {
        return QueueBuilder
                .durable(MqConstant.QUEUE_MODERATION_IMAGE_SUBMIT) // 持久化业务队列
                .deadLetterExchange(MqConstant.EXCHANGE_MODERATION_DLX) // 绑定死信交换机
                .deadLetterRoutingKey(MqConstant.ROUTING_MODERATION_IMAGE_SUBMIT_DLQ) // 死信路由键
                .build();
    }

    /**
     * 图片送审死信队列（DLQ）。
     *
     * 【作用】存放消费失败的图片送审消息（取图失败/DB 超时/发布失败等非预期异常），可追溯、可告警、可重投。
     * 【配置】durable 持久化；无 TTL；无 DLX（避免死信再死信形成环路）。
     */
    @Bean
    public Queue moderationImageSubmitDlq() {
        return QueueBuilder
                .durable(MqConstant.QUEUE_MODERATION_IMAGE_SUBMIT_DLQ) // 持久化死信队列
                .build();
    }

    /**
     * 图片送审队列 ↔ 业务交换机绑定。
     *
     * 【作用】RoutingKey=moderation.image.submit 精确订阅图片送审消息，与文本送审队列物理隔离。
     */
    @Bean
    public Binding moderationImageSubmitBinding(
            TopicExchange moderationExchange,
            Queue moderationImageSubmitQueue) {
        return BindingBuilder
                .bind(moderationImageSubmitQueue) // 绑定图片送审主队列
                .to(moderationExchange) // 到业务 Topic 交换机
                .with(MqConstant.ROUTING_MODERATION_IMAGE_SUBMIT); // 精确路由键
    }

    /**
     * 图片送审死信队列 ↔ 死信交换机绑定。
     *
     * 【作用】Direct + 精确死信 RoutingKey 完成一对一投递，防业务通配规则误吸死信。
     */
    @Bean
    public Binding moderationImageSubmitDlqBinding(
            DirectExchange moderationDlx,
            Queue moderationImageSubmitDlq) {
        return BindingBuilder
                .bind(moderationImageSubmitDlq) // 绑定死信队列
                .to(moderationDlx) // 到死信 Direct 交换机
                .with(MqConstant.ROUTING_MODERATION_IMAGE_SUBMIT_DLQ); // 死信路由键
    }
}
