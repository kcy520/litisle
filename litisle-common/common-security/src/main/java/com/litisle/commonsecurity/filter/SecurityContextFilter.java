package com.litisle.commonsecurity.filter;

import com.litisle.commoncore.constant.HeaderConstant;
import com.litisle.commonsecurity.anonymous.AnonymousAccessHandler;
import com.litisle.commonsecurity.context.SecurityContext;
import com.litisle.commonsecurity.context.SecurityContextHolder;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 业务服务 SecurityContext 过滤器
 *
 * 【设计说明】
 * 1. 部署在业务服务（非网关），从网关注入的 HTTP Header 中解析用户信息
 * 2. 构建 SecurityContext 并写入 SecurityContextHolder（ThreadLocal）
 * 3. 请求结束后清理 ThreadLocal，防止内存泄漏
 * 4. 匿名请求（白名单路径）构建匿名 SecurityContext
 *
 * 【执行流程】
 * 网关校验 JWT 后注入 Header → 业务服务收到请求
 *   → SecurityContextFilter 拦截
 *   ├─ Header 中有用户信息（网关已认证）→ 构建认证上下文
 *   ├─ Header 中无用户信息（匿名路径）→ 检查白名单
 *   │   ├─ 在白名单中 → 构建匿名上下文
 *   │   └─ 不在白名单中 → 构建匿名上下文（容错，不阻塞请求）
 *   └─ 后续业务代码通过 SecurityContextHolder 获取用户信息
 *
 * 【与网关 JwtAuthGlobalFilter 的关系】
 * - 网关：校验 JWT，注入 X-User-Id、X-Roles 等 Header
 * - 业务服务：SecurityContextFilter 从 Header 读取，构建 SecurityContext
 * - 职责分离：网关只做认证，业务服务只做上下文构建
 *
 * 【Order 说明】
 * - 优先级最高（Ordered.HIGHEST_PRECEDENCE + 10）
 * - 在业务层所有切面（@Login、@RequireRole）之前执行
 * - 确保 SecurityContextHolder 在业务代码执行前已初始化
 *
 * @author litisle
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class SecurityContextFilter implements Filter {

    /**
     * 匿名访问处理器（用于判断路径是否在白名单中）
     */
    private final AnonymousAccessHandler anonymousAccessHandler;

    /**
     * 匿名用户角色标识
     */
    private static final String ANONYMOUS_ROLE = "ANONYMOUS";

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse,
                         FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        try {
            // 1. 从 Header 中提取用户信息
            String userIdStr = request.getHeader(HeaderConstant.USER_ID);
            String username = request.getHeader(HeaderConstant.USERNAME);
            String rolesStr = request.getHeader(HeaderConstant.USER_ROLES);
            String permissionsStr = request.getHeader(HeaderConstant.PERMISSIONS);
            String tokenId = request.getHeader(HeaderConstant.TOKEN_ID);
            String traceId = request.getHeader(HeaderConstant.TRACE_ID);

            // 2. 判断是否已认证（网关是否注入了用户信息）
            boolean authenticated = userIdStr != null && !userIdStr.isEmpty();

            if (authenticated) {
                // 2a. 已认证：构建认证 SecurityContext
                // 【说明】认证状态由 SecurityContext.isAuthenticated() 基于 userId != null 推断
                // 不需要显式设置 authenticated 字段（该字段不存在）
                SecurityContext context = SecurityContext.builder()
                        .userId(Long.parseLong(userIdStr))
                        .username(username)
                        .roles(parseCommaSeparated(rolesStr))
                        .permissions(parseCommaSeparated(permissionsStr))
                        .tokenId(tokenId)
                        .traceId(traceId)
                        .build();

                SecurityContextHolder.setContext(context);
                log.debug("SecurityContext 已设置: userId={}, roles={}", userIdStr, rolesStr);
            } else {
                // 2b. 未认证：构建匿名 SecurityContext
                // 【说明】userId = null 时 SecurityContext.isAuthenticated() 自动返回 false
                SecurityContext context = SecurityContext.builder()
                        .userId(null)
                        .username(null)
                        .roles(new HashSet<>(Collections.singletonList(ANONYMOUS_ROLE)))
                        .permissions(Collections.emptySet())
                        .tokenId(null)
                        .traceId(traceId)
                        .build();

                SecurityContextHolder.setContext(context);
                log.debug("匿名 SecurityContext 已设置: path={}", request.getRequestURI());
            }

            // 3. 放行，执行后续业务逻辑
            filterChain.doFilter(servletRequest, servletResponse);

        } catch (Exception e) {
            log.error("SecurityContext 设置异常: {}", e.getMessage(), e);
            // 异常情况下不阻塞请求，继续执行
            filterChain.doFilter(servletRequest, servletResponse);
        } finally {
            // 4. 清理 ThreadLocal，防止内存泄漏
            SecurityContextHolder.clear();
        }
    }

    /**
     * 解析逗号分隔的字符串为 Set
     * 如 "ADMIN,READER" → ["ADMIN", "READER"]
     *
     * @param str 逗号分隔的字符串
     * @return 解析后的 Set（空字符串返回空集）
     */
    private Set<String> parseCommaSeparated(String str) {
        if (str == null || str.isEmpty()) {
            return Collections.emptySet();
        }
        return Arrays.stream(str.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}