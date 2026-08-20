package com.litisle.litislegateway.filter;

import com.litisle.commoncore.constant.HeaderConstant;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 网关 Trace ID 过滤器（链路追踪入口）
 *
 * （不是sa-token的那个上下文：使用eg：String traceId = org.slf4j.MDC.get("traceId");
 *
 * 【设计说明】
 * 1. 为每个请求生成/复用 traceId，注入下游 X-Trace-Id Header，并写入 MDC
 * 2. 优先复用上游传入的 X-Trace-Id（客户端或上游网关），缺失则生成 UUID
 * 3. order=-200，早于 SaReactorFilter(-100)，保证认证失败时也能关联 traceId
 *
 * 【MDC 说明】
 * - WebFlux 响应式线程模型下 MDC 仅对当前线程生效（尽力而为）
 * - 核心目的是注入下游 Header，MDC 供网关自身日志使用
 *
 * 【链路】
 * 客户端 → 网关(本过滤器生成traceId) → 服务A(读Header写MDC) → Feign(读MDC透传) → 服务B
 *
 * @author litisle
 */
@Component
@Order(-200)
public class TraceIdFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // 1. 复用上游 traceId，缺失则生成
        String traceId = exchange.getRequest().getHeaders().getFirst(HeaderConstant.TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        // 2. 注入下游请求 Header + 响应 Header（客户端可关联）
        ServerHttpRequest request = exchange.getRequest().mutate()
                .header(HeaderConstant.TRACE_ID, traceId)
                .build();
        exchange.getResponse().getHeaders().set(HeaderConstant.TRACE_ID, traceId);

        // 3. 写入 MDC（当前线程，尽力而为）
        MDC.put("traceId", traceId);

        return chain.filter(exchange.mutate().request(request).build())
                .doFinally(signalType -> MDC.remove("traceId"));
    }
}