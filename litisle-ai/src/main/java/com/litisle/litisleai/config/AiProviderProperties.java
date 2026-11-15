package com.litisle.litisleai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文本模型通道配置（provider 层唯一接触模型 SDK，连接参数全部外置，代码内不硬编码端点）
 *
 * <p>【配置】前缀 litisle.ai.provider.text；本地默认 enabled=false（api-key 未落位也可启动），
 * 生产/联调经 Nacos 覆盖。换供应商只改 yml（OpenAI 兼容协议：DeepSeek 直接可用）。</p>
 *
 * <p>【安全】api-key 经环境变量/Nacos 注入，禁止提交明文；日志不打印本配置内容。</p>
 *
 * @author litisle
 */
@Data
@ConfigurationProperties(prefix = "litisle.ai.provider.text")
public class AiProviderProperties {

    /**
     * 通道显式开关：true 才装配模型 Bean；false 时提供方端点 fail-fast 返回明确错误码，
     * 不静默吞错（本地无 api-key 也可启动服务）
     */
    private boolean enabled = false;

    /**
     * 通道提供方标识（指标 provider 标签与 ai.guard.state 观测用，如 deepseek）
     */
    private String providerName = "deepseek";

    /** OpenAI 兼容端点（DeepSeek：https://api.deepseek.com/v1） */
    private String baseUrl = "https://api.deepseek.com/v1";

    /** API 密钥（环境变量/Nacos 注入，禁提交明文） */
    private String apiKey = "";

    /** 模型标识（同时作为计量明细 model_version / prompt_version 的取值，文本场景两者同值） */
    private String model = "deepseek-chat";

    /** 单次调用超时（毫秒），超出抛异常 fail-fast */
    private long timeoutMs = 30000;

    /** SDK 内置重试次数（瞬时网络抖动兜底；业务级重试由调用方复用同一 requestId 幂等去重） */
    private int maxRetries = 2;

    /** 默认采样温度（机审/结构化产出要求确定性，默认 0） */
    private Double temperature = 0.0D;
}
