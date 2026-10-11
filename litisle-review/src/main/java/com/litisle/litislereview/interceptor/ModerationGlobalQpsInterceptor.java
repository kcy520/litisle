package com.litisle.litislereview.interceptor;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commonredis.constant.CacheConstant;
import com.litisle.commonredis.ratelimit.RedisRateLimiter;
import com.litisle.litislereview.moderation.config.ModerationProperties;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 审核服务全站 QPS 拦截器（成本护栏 §2.6，选型对齐 file 模块 FileGlobalQpsInterceptor）
 *
 * 这是一个基于 Redis 固定窗口计数、作用在审核服务「全局维度」的 QPS 限流拦截器，用来防止全站洪峰打穿 DB / 下游模型调用，是成本护栏的最后一道闸。
 *
 * 【为什么需要它】
 * @RateLimiter 均为「单用户维度」（防单用户刷），缺「全站总量上限」——
 * 大量不同用户同时打审核接口（人审队列/词库/申诉）时，洪峰会打穿 DB 与下游；
 * 且 3-8.3 故障降级场景下「Redis 缓存全失效 = 机审调用量放大 N 倍」，
 * 全站 QPS 护栏是直调模型不漏审的前提下控制成本的最后一道闸。
 *
 * 【与 per-user @RateLimiter 的关系】
 * - per-user @RateLimiter（如 WordlibController 限频）：防单用户高频（注解式，USER 维度）
 * - 本拦截器：防全站洪峰（拦截器式，global 维度），两者并存互不干扰
 *
 * 【Redis 降级兑底（宁严勿松）】
 * 复用 RedisRateLimiter 固定窗口计数（与注解式限流共用同一 Lua，单一事实来源）；
 * Redis 故障期 RedisRateLimiter 内部自动切 LocalRateLimiter 单机计数（分摊阈值，更严），
 * 本拦截器无需自行处理降级——细案「Redis 降级走本地兑底计数」由 common-redis 承载。
 *
 * 【实现】
 * key = rate_limit:global:moderation:qps；窗口/阈值取 ModerationProperties.Qps
 * （yml: litisle.moderation.qps.*，Nacos 可调）；超限抛 BizException(TOO_MANY_REQUESTS)，
 * 由全局异常处理器统一返回 JSON（与注解式限流错误码一致）。
 * 仅对 REQUEST dispatch 计数，ASYNC 二次 dispatch 直接放行（对齐 file 口径，防御性一致）。
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationGlobalQpsInterceptor implements HandlerInterceptor {

    /**
     * 全站 QPS 限流 key（global 维度，与 per-user key 前缀隔离，互不干扰）
     */
    private static final String GLOBAL_QPS_KEY = CacheConstant.RATE_LIMIT + "global:moderation:qps";

    /** Redis 固定窗口限流器（Redis 故障期内部自动降级本地计数） */
    private final RedisRateLimiter redisRateLimiter;

    /** 限流阈值配置（外置 yml/Nacos，运营可调） */
    private final ModerationProperties moderationProperties;

    /**
     * 全站 QPS 固定窗口计数与拦截
     *
     * @param request  当前 HTTP 请求
     * @param response 响应对象（本拦截器抛异常交由全局处理器返回，不直接写响应）
     * @param handler  目标处理器（未区分类型，全站维度统一计数）
     * @return true 放行；超限抛 BizException(TOO_MANY_REQUESTS) 中断请求
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 仅对首次请求（REQUEST）计数：Spring MVC 异步特性（如 StreamingResponseBody）完成时会触发
        // ASYNC 二次 dispatch，若不排除会把一次请求算成两次，虚高全站计数
        if (DispatcherType.ASYNC.equals(request.getDispatcherType())) {
            return true;
        }

        // 固定窗口计数：返回当前窗口内累计次数（Redis 故障期 RedisRateLimiter 内部切本地兑底计数）
        ModerationProperties.Qps qps = moderationProperties.getQps();
        long currentCount = redisRateLimiter.increment(GLOBAL_QPS_KEY, qps.getWindowSeconds());

        // 超限拦截：抛统一错误码，由全局异常处理器返回 JSON
        if (currentCount > qps.getMaxCount()) {
            log.warn("审核服务全站 QPS 限流触发: uri={}, currentCount={}, maxCount={}",
                    request.getRequestURI(), currentCount, qps.getMaxCount());
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "系统繁忙，请稍后重试");
        }
        return true;
    }
}