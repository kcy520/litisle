package com.litisle.litislecontent.service.support;

import com.litisle.commoncore.constant.HeaderConstant;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commonredis.ratelimit.RedisRateLimiter;
import com.litisle.litislecontent.constant.ContentLimitConstant;
import com.litisle.litislecontent.constant.FeedConstant;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 推荐流频控组件（批次十 10.2，需求条款 9）
 *
 * <p>为什么不用 {@code @RateLimiter} 注解：同一端点要按登录态切换两个维度
 * （登录 60 次/60s 按 USER、匿名 20 次/60s 按 IP），注解只能在编译期固定一个 keyType，
 * 故改为运行时判定身份后调用 {@link RedisRateLimiter} 的固定窗口计数。</p>
 *
 * <p>配额口径：文章流与书籍流共用同一份配额（同一用户在 60s 内刷两条流合计 60 次），
 * 对齐「推荐流总次数」而不是每端点独立计数。</p>
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedRateLimitSupport {

    /** Redis 固定窗口限流器（与注解式限流共用同一 Lua 计数脚本与故障降级） */
    private final RedisRateLimiter redisRateLimiter;

    /**
     * 执行一次频控校验，超限抛业务异常
     *
     * @param userId  登录用户ID（未登录为 null，此时按 IP 维度）
     * @param request HTTP 请求（匿名时用于解析客户端 IP）
     */
    public void check(Long userId, HttpServletRequest request) {
        // 步骤1：按登录态选择维度与阈值
        boolean loggedIn = userId != null;
        // 限流键：登录拼 userId、匿名拼 IP，两类身份互不影响
        String key = buildKey(userId, request);
        // 当前窗口允许的最大次数：登录 60，匿名 20
        int maxCount = loggedIn ? ContentLimitConstant.FEED_MAX_COUNT : ContentLimitConstant.FEED_ANON_MAX_COUNT;

        // 步骤2：固定窗口原子计数（Redis 异常时组件内部自动走本地兜底，不阻断主流程）
        long currentCount = redisRateLimiter.increment(key, ContentLimitConstant.LIMIT_WINDOW_SECONDS);

        // 步骤3：超限拒绝，错误信息交由全局异常处理转统一 Result
        if (currentCount > maxCount) {
            log.warn("推荐流频控触发: key={}, currentCount={}, maxCount={}", key, currentCount, maxCount);
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "推荐流请求过于频繁，请稍后再试");
        }
    }

    /**
     * 构建限流键：登录用 userId、匿名用客户端 IP
     *
     * @param userId  登录用户ID，可为 null
     * @param request HTTP 请求
     * @return Redis 限流键
     */
    private String buildKey(Long userId, HttpServletRequest request) {
        if (userId != null) {
            return FeedConstant.LIMIT_KEY_USER_PREFIX + userId + FeedConstant.LIMIT_KEY_SUFFIX;
        }
        return FeedConstant.LIMIT_KEY_IP_PREFIX + resolveClientIp(request) + FeedConstant.LIMIT_KEY_SUFFIX;
    }

    /**
     * 解析客户端真实 IP（优先级：X-Forwarded-For 首个 > X-Real-IP > RemoteAddr）
     * <p>与 common-redis 限流 Key 构建器同口径；IP 可信性由网关/Nginx 层保证，本方法只负责解析</p>
     *
     * @param request HTTP 请求
     * @return 客户端 IP；解析失败返回 unknown（同 IP 汇聚计数，偏严不偏松）
     */
    private String resolveClientIp(HttpServletRequest request) {
        // 优先取代理头 X-Forwarded-For，多级代理时取第一个（客户端）地址
        String ip = request.getHeader(HeaderConstant.X_FORWARDED_FOR);
        if (StringUtils.hasText(ip)) {
            ip = ip.split(",")[0].trim();
        }
        // 代理头无效时回退 X-Real-IP
        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader(HeaderConstant.X_REAL_IP);
        }
        // 仍无效则取直连地址
        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return StringUtils.hasText(ip) ? ip : "unknown";
    }
}
