package com.litisle.litislefile.interceptor;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commonredis.constant.CacheConstant;
import com.litisle.commonredis.ratelimit.RedisRateLimiter;
import com.litisle.litislefile.config.FileProperties;
import com.litisle.litislefile.constant.FileLimitConstant;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 文件服务全站 QPS 拦截器（系统级限流，选型 a）
 *
 * 【为什么需要它】
 * 现有 @RateLimiter 均为「单用户维度」（防单用户刷），缺「全站总量上限」——
 * 大量不同用户同时打 /api/file/** 时，洪峰会打爆 Tomcat 线程池 / SeaweedFS 后端。
 * 本拦截器对 /api/file/** 做全站 QPS 上限，与 per-user @RateLimiter 并存、互不干扰：
 * - per-user @RateLimiter：防单用户高频（注解式，USER 维度）
 * - 本拦截器：防全站洪峰（拦截器式，global 维度）
 *
 * 【为何用拦截器而非注解】
 * @RateLimiter 不可重复，同一方法无法同时叠加「用户级 + 全局级」；
 * 拦截器按路径统一注册，语义最清晰、对业务代码零侵入。
 *
 * 【实现】
 * 复用 RedisRateLimiter 的固定窗口计数（与注解式限流共用同一 Lua，单一事实来源）：
 * key = rate_limit:global:file:qps，窗口/阈值取 FileProperties.Limit（yml: litisle.file.limit，Nacos 可调）；
 * 超限抛 BizException(TOO_MANY_REQUESTS)，
 * 由全局异常处理器统一返回 JSON（与注解式限流错误码一致）。
 * 仅对 REQUEST dispatch 计数；ASYNC 二次 dispatch（StreamingResponseBody 下载）直接放行，避免一次下载被重复计数。
 *
 * 【取舍】固定窗口在边界存在突刺（两窗口交界处瞬时可达 2×阈值），对「全站总量保护」足够；
 * 若需精确平滑，应改滑动窗口/令牌桶（当前项目未引入）。
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileGlobalQpsInterceptor implements HandlerInterceptor {

    /**
     * 全站 QPS 限流 key（global 维度，与 per-user key 前缀隔离，互不干扰）
     */
    private static final String GLOBAL_QPS_KEY = CacheConstant.RATE_LIMIT + "global:file:qps";

    private final RedisRateLimiter redisRateLimiter;

    /**
     * 限流阈值配置（外置 yml/Nacos，运营可调）
     */
    private final FileProperties fileProperties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 仅对首次请求（REQUEST）计数：StreamingResponseBody 完成时会触发 ASYNC 二次 dispatch，
        // 若不排除会把一次下载算成两次，既虚高全站计数，又与 @RateLimiter 的「一次」口径不一致
            //这是为了防止 StreamingResponseBody 触发的 ASYNC 二次请求分发，导致一次下载被全站 QPS 拦截器算成两次。
            // 现象：为什么会被算两次？
            // 一次下载，在 Spring MVC 内部其实是 两次 dispatch：
            //   阶段	                    DispatcherType	         说明
            // ① 客户端发起请求	                REQUEST                 正常 HTTP 请求
            // ② StreamingResponseBody 写数据	ASYNC	               异步线程写完后再 dispatch 回 MVC
        if (DispatcherType.ASYNC.equals(request.getDispatcherType())) {
            return true;
        }

        // 固定窗口计数：windowSeconds 传数字类型（见 RedisRateLimiter 说明）
        FileProperties.Limit limit = fileProperties.getLimit();
        long currentCount = redisRateLimiter.increment(GLOBAL_QPS_KEY, limit.getGlobalQpsWindowSeconds());

        if (currentCount > limit.getGlobalQpsMaxCount()) {
            log.warn("全站 QPS 限流触发: uri={}, currentCount={}, maxCount={}",
                    request.getRequestURI(), currentCount, limit.getGlobalQpsMaxCount());
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "文件服务繁忙，请稍后重试");
        }
        return true;
    }

}
