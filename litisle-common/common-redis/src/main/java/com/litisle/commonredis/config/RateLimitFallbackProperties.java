package com.litisle.commonredis.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 限流故障降级配置（对应 yml: litisle.rate-limit.fallback，Nacos 可覆盖）
 *
 * 背景：RedisRateLimiter 统一 fail-open 后，「Redis 挂 → 限流失效」对防爆破类
 * 接口（登录/验证码）是防护缺口。定稿为轻量企业版本地兜底（不引 Sentinel，
 * 遵循 common-redis 库模块轻量约束），本类承载其可调参数。
 *
 * 注意事项：
 * - instance-count 依赖人工配准，实例扩缩容后需同步
 *   （后续可接 Nacos 服务发现动态计算实例数）
 * - enabled=false 时回退为纯 fail-open（Redis 故障放行），仅建议本地调试关闭
 *
 * @author litisle
 */
@Data
@ConfigurationProperties(prefix = "litisle.rate-limit.fallback")
public class RateLimitFallbackProperties {

    /** 是否启用本地兜底限流；false 时 Redis 故障回退为纯 fail-open（放行） */
    private boolean enabled = true;

    /** 熔断 OPEN 冷却期（秒）：冷却到期进入 HALF_OPEN 放单个探测请求试 Redis */
    private long cooldownSeconds = 5L;

    /** 集群实例数：本地配额分摊 = 集群阈值 ÷ 实例数；扩缩容后需同步 */
    private int instanceCount = 1;
}