package com.litisle.commonsecurity.trace;

import com.litisle.commoncore.constant.HeaderConstant;
import com.litisle.commoncore.utils.TraceIdSanitizer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 业务服务 Trace ID 过滤器（链路追踪承接）
 *
 * 【设计说明】
 * 1. 从请求 Header 读取 X-Trace-Id（网关注入），写入 MDC
 * 2. 若上游未传（直接访问、内部定时任务等），则本地生成
 * 3. FeignAuthInterceptor 从 MDC 读取 traceId 透传给下游服务
 *
 * 【链路】
 * 网关(生成traceId) → 服务A(本过滤器读Header写MDC) → Feign(读MDC透传) → 服务B(本过滤器读Header写MDC)
 *
 * 【为什么用 OncePerRequestFilter】
 * - 保证一次请求只执行一次（Servlet 容器可能多次调用 Filter）
 * - 与 XssFilter 的 FilterRegistrationBean 注册方式一致
 *
 * @author litisle
 */
public class TraceIdFilter extends OncePerRequestFilter {


//    TraceIdFilter 的作用域仅限于 HTTP 请求线程
//    MQ 是一条「异步边界」，MDC 不会自动跨线程、跨进程、跨 broker。
//    HTTP Filter 只能保证「请求内」链路；MQ 是异步边界，traceId 必须显式跨线程、跨进程传递。

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // 1. 复用上游 traceId（先过 TraceIdSanitizer 净化剥离 \r\n 等控制字符，防 CRLF 日志注入），
        //    缺失则生成；生成口径统一收敛到 sanitizeOrGenerate（SSOT，与网关 TraceIdFilter 一致）
        String traceId = TraceIdSanitizer.sanitizeOrGenerate(request.getHeader(HeaderConstant.TRACE_ID));

        // 2. 写入 MDC（FeignAuthInterceptor 从此读取透传）
        MDC.put("traceId", traceId);

        // 3. 响应 Header 回写 traceId（客户端可关联）
        response.setHeader(HeaderConstant.TRACE_ID, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
        }
    }
}