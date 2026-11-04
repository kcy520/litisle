package com.litisle.litisleuser.config;

import com.litisle.litisleapi.constant.MqConstant;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * user 域 MQ 拓扑配置（批次五 B5）。
 *
 * 【职责】只声明 user 域事件所用的主题交换机；队列与绑定由消费方（search）按自身消费需求声明，
 * 生产方不感知下游队列，避免下游新增消费者时改生产方代码。
 *
 * 【声明时机】交换机为持久化 + 非自动删除，RabbitAdmin 启动时幂等声明（已存在则跳过）。
 *
 * @author litisle
 */
@Configuration
public class UserMqConfig {

    /**
     * 声明用户域主题交换机（用户注册/资料更新/封禁等事件共用）。
     *
     * @return 持久化、非自动删除的主题交换机
     */
    @Bean
    public TopicExchange userExchange() {
        // durable=true 持久化（broker 重启不丢交换机定义）；autoDelete=false 无消费者时不自动删除
        return new TopicExchange(MqConstant.EXCHANGE_USER, true, false);
    }
}
