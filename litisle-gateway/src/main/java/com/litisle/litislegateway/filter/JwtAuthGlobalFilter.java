package com.litisle.litislegateway.filter;

import com.litisle.commoncore.constant.HeaderConstant;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commonsecurity.jwt.JwtConstant;
import com.litisle.commonsecurity.jwt.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * 网关 JWT 认证全局过滤器
 *
 * 【设计说明】
 * 1. 作为 Spring Cloud Gateway 的 GlobalFilter，拦截所有进入网关的请求
 * 2. 校验 JWT 令牌的有效性，提取用户信息注入下游请求 Header
 * 3. 白名单路径跳过 JWT 校验（如登录、注册、验证码等）
 * 4. 令牌校验失败返回 401，不转发到下游服务
 *
 * 【执行流程】
 * 客户端请求 → JwtAuthGlobalFilter
 *   ├─ 白名单路径？ → 是 → 放行（追加 traceId）
 *   ├─ 否 → 提取 Authorization Header
 *   │   ├─ 无 Token？ → 返回 401 UNAUTHORIZED
 *   │   └─ 解析 Token（JwtTokenProvider）
 *   │       ├─ 解析失败？ → 返回 401 TOKEN_EXPIRED / TOKEN_INVALID
 *   │       └─ 解析成功 → 注入用户信息 Header → 放行
 *
 * 【注入下游 Header 列表】
 * - X-User-Id：用户ID
 * - X-Username：用户名
 * - X-Roles：角色列表（逗号分隔）
 * - X-User-Permissions：权限列表（逗号分隔）
 * - X-Token-Id：令牌唯一标识（jti）
 * - X-Trace-Id：链路追踪 ID
 *
 * 【Ordered 说明】
 * - 优先级最高（-200），在路由转发之前执行
 * - 确保认证在业务逻辑之前完成
 *
 * 【响应式设计】
 * - 使用 Spring WebFlux 的 ServerWebExchange，非阻塞 I/O
 * - 不依赖 Servlet API，与网关的 WebFlux 架构一致
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {

    private final JwtTokenProvider jwtTokenProvider;

    /**
     * 匿名访问白名单路径列表
     */
    private final List<String> anonymousPaths;

    /**
     * Ant 路径匹配器（线程安全）
     */
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * 过滤器优先级：-200（最高优先级，路由转发之前执行）
     */
    @Override
    public int getOrder() {
        return -200;
    }

    /**
     * 核心过滤逻辑
     *
     * @param exchange 当前请求-响应交换对象（WebFlux）
     * @param chain    过滤器链
     * @return Mono<Void> 响应式结果
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // Step 1: 检查是否在白名单中（跳过 JWT 校验）
        if (isAnonymousPath(path)) {
            log.debug("白名单路径放行: {}", path);
            ServerHttpRequest mutatedRequest = appendTraceId(request);
            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        }

        // Step 2: 提取 Authorization Header
        String authHeader = request.getHeaders().getFirst(JwtConstant.AUTHORIZATION_HEADER);
        if (authHeader == null || !authHeader.startsWith(JwtConstant.BEARER_PREFIX)) {
            log.warn("缺少 Authorization Header: path={}", path);
            return unauthorized(exchange, ErrorCode.UNAUTHORIZED, "缺少认证令牌");
        }

        // Step 3: 提取 Bearer Token
        String token = authHeader.substring(com.litisle.commonsecurity.jwt.JwtConstant.BEARER_PREFIX_LENGTH);
        if (!StringUtils.hasText(token)) {
            log.warn("Authorization Header 中 Token 为空: path={}", path);
            return unauthorized(exchange, ErrorCode.UNAUTHORIZED, "认证令牌为空");
        }

        // Step 4: 解析 JWT 令牌
        Claims claims;
        try {
            claims = jwtTokenProvider.parseToken(token);
        } catch (BizException e) {
            log.warn("JWT 校验失败: path={}, code={}, message={}", path, e.getCode(), e.getMessage());
            return unauthorized(exchange, ErrorCode.getByCode(e.getCode()), e.getMessage());
        }

        // Step 5: 提取用户信息，注入下游 Header
        String userId = claims.get(JwtConstant.CLAIM_USER_ID, String.class);
        String username = claims.get(JwtConstant.CLAIM_USERNAME, String.class);
        String roles = claims.get(JwtConstant.CLAIM_ROLES, String.class);
        String permissions = claims.get(JwtConstant.CLAIM_PERMISSIONS, String.class);
        String tokenId = claims.getId();
        String traceId = request.getHeaders().getFirst(HeaderConstant.TRACE_ID);

        // 构建新的请求，注入用户信息 Header
        ServerHttpRequest mutatedRequest = request.mutate()
                .header(HeaderConstant.USER_ID, nullSafe(userId))
                .header(HeaderConstant.USERNAME, nullSafe(username))
                .header(HeaderConstant.USER_ROLES, nullSafe(roles))
                .header(HeaderConstant.PERMISSIONS, nullSafe(permissions))
                .header(HeaderConstant.TOKEN_ID, nullSafe(tokenId))
                .header(HeaderConstant.TRACE_ID, traceId != null ? traceId : generateTraceId())
                .build();

        log.debug("JWT 认证通过: userId={}, path={}", userId, path);

        // Step 6: 放行，转发到下游服务
        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    /**
     * 判断请求路径是否在白名单中
     */
    private boolean isAnonymousPath(String path) {
        if (anonymousPaths == null || anonymousPaths.isEmpty()) {
            return false;
        }
        return anonymousPaths.stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    /**
     * 追加链路追踪 ID（traceId）
     */
    private ServerHttpRequest appendTraceId(ServerHttpRequest request) {
        String traceId = request.getHeaders().getFirst(HeaderConstant.TRACE_ID);
        if (traceId == null) {
            return request.mutate()
                    .header(HeaderConstant.TRACE_ID, generateTraceId())
                    .build();
        }
        return request;
    }

    /**
     * 生成链路追踪 ID
     * 格式：时间戳 + 随机字符串
     */
    private String generateTraceId() {
        return System.currentTimeMillis() + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /**
     * 空安全处理：null 转为空字符串
     */
    private String nullSafe(String value) {
        return value != null ? value : "";
    }

    /**
     * 返回 401 未授权响应（JSON 格式）
     */
    private Mono<Void> unauthorized(ServerWebExchange exchange, ErrorCode errorCode, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8");

        String body = String.format(
                "{\"code\":%d,\"message\":\"%s\",\"data\":null}",
                errorCode.getCode(),
                message != null ? escapeJson(message) : errorCode.getMessage()
        );

        return response.writeWith(
                Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8)))
        );
    }

    /**
     * JSON 字符串转义（防止注入）
     */
    private String escapeJson(String value) {
        if (value == null) return "";
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}