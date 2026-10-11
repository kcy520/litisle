package com.litisle.litisleauth.mq;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 处罚结果消费容器工厂（临时清单 B4，5-3.1 串行消费配置）
 *
 * 【串行语义】punish.result 队列固定单消费者串行消费：消费编排为「回查→覆盖快照→kickout」，
 * 覆盖式写入本身可乱序，但「不依赖到达顺序」的前提是同一用户的事件不被并发处理——
 * 单消费者（concurrency=1）天然串行；prefetch=1 让消费节奏与处理节奏对齐，
 * 避免单实例内多线程交错
 *
 * 【扩容口径（预留扩展点，一期单实例不启用）】
 * 多实例扩容时 RabbitMQ 同一队列仍按投递粒度轮询分发，无法保证同 userId 事件路由到
 * 同一实例——需启用 x-consistent-hash 交换机按 userId 一致性哈希路由（每个实例一条专属队列），
 * 届时：① 新建 EXCHANGE_PUNISH_HASH（x-consistent-hash，weight=1）替换本类绑定拓扑；
 * ② 消费端按实例数声明 queue-{instanceIndex}；③ 覆盖式快照语义不变，无需额外改动。
 * 本期固定单消费者，扩容触发条件：punish 事件 TPS 使单实例消费积压持续 > 阈值（5-3.1）
 *
 * 【关键坑】Spring Boot 自动配置仅对名为 rabbitListenerContainerFactory 的主工厂自动装配
 * MessageConverter；本自定义工厂必须显式 set common-mq 的 jacksonMessageConverter Bean
 * （@ConditionalOnMissingBean 保护，容器内唯一），否则 PunishResultEvent 反序列化失败成毒丸
 *
 * @author litisle
 */
@Configuration
public class PunishConsumeConfig {

    /**
     * 处罚结果专属监听容器工厂：单消费者 + prefetch=1 + 手动 ACK + 显式 Jackson 转换器
     * <p>供 PunishResultConsumer.@RabbitListener(containerFactory=...) 指定；
     * 其余监听属性（连接/重试）沿用 yml 自动配置基准（configurer 复用）</p>
     */
    @Bean
    public SimpleRabbitListenerContainerFactory punishListenerContainerFactory(
            ConnectionFactory connectionFactory,
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            org.springframework.amqp.support.converter.MessageConverter jacksonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        // 沿用 yml 自动配置基准（连接参数/默认属性），再覆盖串行专项参数
        configurer.configure(factory, connectionFactory);
        // 串行核心：固定 1 个消费者（concurrentConsumers=1，无 max 表示永不扩容）
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        // prefetch=1：每条消息处理完才取下一条，消费节奏与处理节奏对齐（串行语义兜底）
        factory.setPrefetchCount(1);
        // 手动 ACK：ack/nack 由 MqAckHelper 模板统一收口（重试感知/毒丸防护）
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        // 关键：自定义工厂必须显式挂 Jackson 转换器（自动配置仅覆盖主工厂，见类注【关键坑】）
        factory.setMessageConverter(jacksonMessageConverter);
        return factory;
    }
}
