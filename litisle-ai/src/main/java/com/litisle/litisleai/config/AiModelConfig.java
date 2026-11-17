package com.litisle.litisleai.config;

import com.litisle.litisleai.metrics.AiMetrics;
import com.litisle.litisleapi.config.AiLabelDimensionsProperties;
import com.litisle.litisleai.provider.text.CircuitBreakerChatModel;
import com.litisle.litisleai.provider.text.DesensitizingChatModel;
import com.litisle.litisleai.provider.text.OutboundSanitizer;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/*
 * 通俗说明：
 * 这个类负责创建"真正去调用大模型的那个客户端"。
 * 用 OpenAI 通用协议对接 DeepSeek 等服务商，并在外面包一层"脱敏装饰"——
 * 发给模型之前先把敏感信息替换掉，这层可以通过配置一键关掉。
 * litisle.ai.provider.text.enabled=false 时不创建客户端，没有 api-key 的本地环境也能启动，
 * 调用方会收到明确的错误码，而不是空指针崩溃。
 */
/**
 * AI 模型通道装配（板块一 1.1：模型 SDK Bean 的唯一装配点；板块二叠加出站脱敏装饰器）
 *
 * <p>【配置】litisle.ai.provider.text.* 与 litisle.ai.agent.* 全部外置（yml/Nacos），
 * api-key 经环境变量注入；enabled=false（本地默认）时不装配模型 Bean，服务其余功能不受影响。</p>
 *
 * <p>【装配说明】P2 定档采用 LangChain4j 官方 BOM + OpenAI 兼容通道；此处用显式 @Bean +
 * enabled 开关替代 starter 自动装配：本地无 api-key 也能启动服务（通道缺席时端点
 * fail-fast 返回明确错误码），且超时/重试参数与配置类单向对齐，避免双配置源漂移。</p>
 *
 * <p>【装饰链（板块二 P1 / 板块六 6.2）】容器唯一 ChatModel Bean = DesensitizingChatModel（出站脱敏）
 * → CircuitBreakerChatModel（熔断自检：连续失败达阈值熔断、到期半开探测自动切回）
 * → OpenAiChatModel（SDK）：机审通道（TextModelProvider）与 Agent 通道（AiServices）
 * 出站统一经过熔断与脱敏装饰器，开关关闭时透传。</p>
 *
 * @author litisle
 */
@Configuration
@EnableConfigurationProperties({AiProviderProperties.class, AiAgentProperties.class, AiLabelDimensionsProperties.class})
public class AiModelConfig {

    /**
     * 出站脱敏装饰模型 Bean（容器唯一 ChatModel：装饰器 → 熔断自检 → OpenAI 兼容通道）
     *
     * @param properties      通道配置（enabled=true 时本 Bean 才装配；含熔断阈值/窗口/时长）
     * @param sanitizer       出站脱敏过滤器
     * @param agentProperties Agent 侧配置（脱敏开关）
     * @param metrics         指标打点（熔断自检装饰器注册 ai_guard_state Gauge 用）
     * @return ChatModel 通道实例（脱敏 + 熔断装饰链）
     */
    @Bean
    @ConditionalOnProperty(prefix = "litisle.ai.provider.text", name = "enabled", havingValue = "true")
    public ChatModel desensitizedChatModel(AiProviderProperties properties,
                                           OutboundSanitizer sanitizer,
                                           AiAgentProperties agentProperties,
                                           AiMetrics metrics) {
        // 底层 OpenAI 兼容通道（DeepSeek 直接可用）：连接参数全部取自外置配置，代码内不硬编码端点
        ChatModel delegate = OpenAiChatModel.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .modelName(properties.getModel())
                .temperature(properties.getTemperature())
                .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                .maxRetries(properties.getMaxRetries())
                .build();
        // 熔断自检装饰器（板块六 6.2）：机审/Agent 全通道统一经过——连续失败达阈值熔断期内
        // 直接拒绝（机审侧 review 转人工兜底，生成侧抛 80005），到期半开探测成功自动恢复切回
        delegate = new CircuitBreakerChatModel(delegate, properties, metrics);
        // 出站脱敏装饰器：机审/Agent 全通道统一执行（开关关闭时透传）
        return new DesensitizingChatModel(delegate, sanitizer, agentProperties);
    }
}
