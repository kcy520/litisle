package com.litisle.commonmq.config;


import com.litisle.commonmq.send.MqSendUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * common-mq 自动配置（经 AutoConfiguration.imports 注册，业务服务引依赖即生效）。
 *
 * 【注册内容】
 * - Jackson2JsonMessageConverter：事件体 JSON 序列化统一口径（项目安全纪律：禁 Java 原生反序列化）。
 *   Spring Boot RabbitAutoConfiguration 会自动探测该 Bean 并装配到 RabbitTemplate
 *   （@ConditionalOnSingleCandidate(MessageConverter)），业务服务无需再自行声明，
 *   也不需要自定义 RabbitTemplate Bean
 * - MqSendUtil：发送封装（traceId 注入 + 失败日志口径统一）
 *
 * 【业务服务的既有约定】
 * - MqConstant / 事件 DTO 由业务服务自行依赖 litisle-api 提供
 * - 拓扑声明（Exchange/Queue/Binding/DLX）与 yml 连接配置留在各业务服务，common-mq 不抽
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(RabbitTemplate.class)
public class CommonMqAutoConfiguration {

    /** 事件体 JSON 序列化（发布方与消费方统一口径；禁 Java 原生反序列化） */
    @Bean
    @ConditionalOnMissingBean(MessageConverter.class)
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * MQ 发送工具：MDC traceId 注入消息 Header + 发送失败仅记 error 不抛
     * （对齐业务侧「事件发布失败不回滚主流程」纪律，如 file 上传 safe_flag 停留占位态）
     */
    @Bean
    @ConditionalOnMissingBean
    public MqSendUtil mqSendUtil(RabbitTemplate rabbitTemplate) {
        return new MqSendUtil(rabbitTemplate);
    }
}
