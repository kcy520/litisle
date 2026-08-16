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
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

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
//@Component + implements Filter 会导致 Spring Boot 自动将 Filter 注册到 /* 。
// 不过没关系 ：Spring Boot 的规则是——如果同一个 Filter 同时有 @Component 自动注册和 FilterRegistrationBean 手动注册， FilterRegistrationBean 优先，自动注册被抑制 。所以最终拦截路径是 /api/* ，不是 /* 。
@Slf4j
public class SecurityContextFilter implements Filter {

    private final AnonymousAccessHandler anonymousAccessHandler;

    public SecurityContextFilter(AnonymousAccessHandler anonymousAccessHandler) {
        this.anonymousAccessHandler = anonymousAccessHandler;
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse,
                         FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        String requestPath = request.getRequestURI();

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
                // 2b. 未认证：检查是否在白名单中，决定是否构建匿名上下文
                if (anonymousAccessHandler.isAnonymous(requestPath)) {
                    // 白名单路径：构建匿名上下文
                    anonymousAccessHandler.setupAnonymousContext();
                    log.debug("匿名路径，已设置匿名 SecurityContext: path={}", requestPath);
                } else {
                    // 非白名单路径：理论上不会发生（网关已校验 JWT），容错构建匿名上下文
                    anonymousAccessHandler.setupAnonymousContext();
                    log.warn("非匿名路径缺少用户信息，容错构建匿名上下文: path={}", requestPath);
                }
            }

            // 3. 放行，执行后续业务逻辑
            filterChain.doFilter(servletRequest, servletResponse);

        } finally {
            // 4. 清理 ThreadLocal，防止内存泄漏
            SecurityContextHolder.clear();
        }
    }

    /**
     * 注册 SecurityContextFilter
     *
     * 【设计说明】
     * 1. 使用 @Configuration + FilterRegistrationBean 注册，与 XssFilter 写法一致
     * 2. 拦截 /api/* 路径，排除 actuator、静态资源等
     * 3. 设置最高优先级，在业务切面之前执行
     * 4. 与 XssFilter 的顺序关系：
     *    - SecurityContextFilter (Ordered.HIGHEST_PRECEDENCE + 10) 先执行
     *    - XssFilter (order=1) 后执行
     *    - 顺序：认证上下文 → XSS 清洗 → 业务逻辑
     */
    @Configuration
    public static class SecurityContextFilterConfig {

        @Bean
        public FilterRegistrationBean<SecurityContextFilter> securityContextFilterRegistration(
                AnonymousAccessHandler anonymousAccessHandler) {
            FilterRegistrationBean<SecurityContextFilter> registration = new FilterRegistrationBean<>();
            registration.setFilter(new SecurityContextFilter(anonymousAccessHandler));
            registration.addUrlPatterns("/api/*");
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
            registration.setName("securityContextFilter");
            return registration;
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