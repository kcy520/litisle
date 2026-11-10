package com.litisle.litislecontent.service.support;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commonredis.ratelimit.RateLimiter;
import com.litisle.commonredis.ratelimit.RedisRateLimiter;
import com.litisle.litislecontent.config.ContentProperties;
import com.litisle.litislecontent.constant.ContentBizConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 点赞频控组件（批次十一 11.8）。
 *
 * <p>为什么不用 {@code @RateLimiter} 注解：注解的 count 只能是编译期常量，无法满足
 * 「阈值可配置」的要求。本组件在运行时读取 {@code litisle.content.like.rate-limit-*}
 * （yml / Nacos 可调），并复用与注解完全相同的 Redis 固定窗口计数脚本和 USER 维度 key 风格
 * （rate_limit:like:user:{userId}）。</p>
 *
 * <p>配额口径：点赞与取消点赞共用同一份单用户配额，防止用户快速反复切换刷写库。</p>
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LikeRateLimitSupport {

    /** Redis 固定窗口限流器（与注解式限流共用同一 Lua 计数脚本与故障降级） */
    private final RedisRateLimiter redisRateLimiter;

    /** content 模块配置（读取点赞频控窗口与阈值） */
    private final ContentProperties contentProperties;


// key = rate_limit:like:user:{userId}      ← 每个用户一个独立计数器
// 窗口 = rate-limit-window-seconds（如 60 秒）
// 上限 = rate-limit-max-count（如 30 次）

// 用户每次点赞/取消 → INCR key（原子 +1，首次附带窗口过期时间）
//                   → 计数 > 上限 → 抛 TOO_MANY_REQUESTS
// 窗口到期 key 自动过期 → 计数器归零，重新开始计数

// 为什么不用`@RateLimiter` 注解？
// LikeRateLimitSupport.java#L14-19 类注释直接说明了：注解的`count` 属性只能是 编译期常量 ，写死在代码里。而这里要求阈值可通过 yml / Nacos 运行时热调 ——比如发现刷子攻击，运维不改代码、不重启，直接在配置中心把 30 调成 5。
  


/**
     * 执行一次点赞频控校验，超限抛业务异常。
     *
     * @param userId 点赞用户ID（Controller @SaCheckLogin 保证非空）
     * @throws BizException TOO_MANY_REQUESTS 窗口内操作次数超过配置上限
     */
    public void check(Long userId) {
        // 步骤1：读取可配置的窗口与阈值（配置缺失时 ContentProperties 内已有默认值）
        ContentProperties.Like likeConfig = contentProperties.getLike();
        // 频控窗口（秒）
        long windowSeconds = likeConfig.getRateLimitWindowSeconds();
        // 单用户窗口内允许的最大次数
        int maxCount = likeConfig.getRateLimitMaxCount();
        // 单用户维度限流键（点赞与取消点赞共用，合计计数）
        String key = ContentBizConstant.LIKE_RATE_LIMIT_KEY_PREFIX + userId;

        // 步骤2：固定窗口原子计数（Redis 异常时组件内部自动走本地兜底，不阻断主流程）
        long currentCount = redisRateLimiter.increment(key, windowSeconds);

        // 步骤3：超限拒绝，错误信息交由全局异常处理转统一 Result
        if (currentCount > maxCount) {
            log.warn("点赞频控触发: userId={}, currentCount={}, maxCount={}", userId, currentCount, maxCount);
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "点赞操作过于频繁，请稍后再试");
        }
    }
}
