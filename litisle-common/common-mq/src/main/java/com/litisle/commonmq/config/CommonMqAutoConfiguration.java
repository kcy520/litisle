package com.litisle.commonmq.config;


import com.litisle.commonmq.consume.MqIdempotentHelper;
import com.litisle.commonmq.metrics.MqMetrics;
import com.litisle.commonmq.send.MqSendUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;

// 注：MeterRegistry 与 StringRedisTemplate 均为 provided 依赖，仅 Bean 方法签名引用，
// @ConditionalOnClass 守卫保证 classpath 缺失时跳过注册

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

    /**
     * 事件体 JSON 序列化器（发布方与消费方统一口径；禁 Java 原生反序列化）。
     *
     * <p>@ConditionalOnMissingBean(MessageConverter)：容器中已有自定义 MessageConverter 时不注册，
     * 让位业务服务自定义（保护性兜底，正常情况业务服务不应自建）。
     *
     * <p>生效链路：此 Bean 注册后，Spring Boot 的 RabbitAutoConfiguration 会自动探测唯一候选的
     * MessageConverter 并装配到 RabbitTemplate（@ConditionalOnSingleCandidate(MessageConverter)），
     * 因此【不需要】再定义 RabbitTemplate Bean。</p>
     */
    @Bean
    @ConditionalOnMissingBean(MessageConverter.class)
    public MessageConverter jacksonMessageConverter() {
        // Jackson2JsonMessageConverter：消息体 ↔ JSON 双向转换，
        // 发送时 Java 对象序列化为 JSON 字节，消费时按监听方法参数类型反序列化回事件 DTO
        return new Jackson2JsonMessageConverter();
    }

    /**
     * MQ 发送工具 Bean：traceId 注入消息 Header + 发送失败仅记 error 不抛。
     *
     * <p>依赖注入 RabbitTemplate（由 Spring Boot RabbitAutoConfiguration 按连接配置自动创建），
     * 包一层统一收口的发送逻辑（见 MqSendUtil#send）。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public MqSendUtil mqSendUtil(RabbitTemplate rabbitTemplate) {
        return new MqSendUtil(rabbitTemplate);
    }

    /**
     * MQ 幂等去重辅助 Bean（预置工具，按需使用）。
     *
     * <p>@ConditionalOnClass(StringRedisTemplate.class)：消费方未引入 redis starter 时
     * （provided 依赖不传递，classpath 无该类）整个 Bean 跳过注册，不影响模块加载；
     * 引入 redis starter 的消费方自动获得 Bean。</p>
     *
     * <p>ObjectProvider optional 注入：容器内无 StringRedisTemplate Bean（如引了 starter
     * 但未配 Redis 连接）时 getIfAvailable() 返回 null，Helper 内部降级，不阻断启动。</p>
     *
     * <p>TTL 外置配置：litisle.mq.idempotent.dedup-ttl-seconds，默认 86400（24h，
     * 覆盖 MQ 常规重投窗口；DLQ 人工重驱动相隔数日的场景按需调大）。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(StringRedisTemplate.class)
    public MqIdempotentHelper mqIdempotentHelper(
            ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider,
            @Value("${litisle.mq.idempotent.dedup-ttl-seconds:86400}") long dedupTtlSeconds) {
        return new MqIdempotentHelper(stringRedisTemplateProvider.getIfAvailable(), dedupTtlSeconds);
    }

    /**
     * MQ 收发指标埋点 Bean（预置工具，按需使用；注入方式对齐 RedisGuardMetrics 的
     * ObjectProvider&lt;MeterRegistry&gt; 可选注入）。
     *
     * <p>@ConditionalOnClass(MeterRegistry.class)：消费方未引 micrometer 时（provided 不传递）
     * 跳过注册；已引 actuator 的服务自动获得。容器内无 MeterRegistry Bean 时注入 null，
     * MqMetrics 全方法静默空转（不埋点不报错）。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(MeterRegistry.class)
    public MqMetrics mqMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new MqMetrics(meterRegistryProvider.getIfAvailable());
    }
}
