package com.litisle.commonredis.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 限流故障降级配置（对应 yml: litisle.rate-limit.fallback，Nacos 可覆盖）
 *
 * 背景：RedisRateLimiter 统一 fail-open 后，「Redis 挂 → 限流失效」对防爆破类
 * 接口（登录/验证码）是防护缺口。定稿为轻量熔断 + 本地兜底（不引 Sentinel，
 * 遵循 common-redis 库模块轻量约束），本类承载其可调参数。
 *
 * 注意事项：
 * - cooldown-seconds 已映射为 Resilience4j `waitDurationInOpenState`（熔断 OPEN 冷却期）
 * - instance-count 依赖人工配准，实例扩缩容后需同步
 *   （后续可接 Nacos 服务发现动态计算实例数）
 * - enabled=false 时只跳过熔断器调度（不申请许可、不切换三态），
 *   每个请求仍直连 Redis，Redis 异常时依旧回退本地计数兜底；
 *   代价是故障期每个请求都要吃一次连接超时，仅建议本地调试时关闭

 *
 * @author litisle
 */
@Data
@ConfigurationProperties(prefix = "litisle.rate-limit.fallback")
public class RateLimitFallbackProperties {

    /**
     * 熔断降级总开关：true 走「熔断器 + 本地兜底」；
     * false 只跳过熔断器调度（恒走 Redis，异常时仍本地计数兜底，不做三态切换）
     */
    private boolean enabled = true;

    /**
     * 熔断 OPEN 冷却期（秒）
     * 映射到 Resilience4j CircuitBreaker 的 waitDurationInOpenState：
     * 冷却到期后自动转入 HALF_OPEN，仅放行 1 个探测许可
     */
    private long cooldownSeconds = 5L;

    /** 集群实例数：本地配额分摊 = 集群阈值 ÷ 实例数；扩缩容后需同步 */
    private int instanceCount = 1;
}