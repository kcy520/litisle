package com.litisle.commonsecurity.xss;

e:\NewJavaPro\LitIsle\litisle-common\common-security\src\main\java\com\litisle\commonsecurity\xss\XssFilter.java
package com.litisle.commonsecurity.xss;

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

import java.io.IOException;

/**
 * XSS 过滤器
 *
 * 【设计说明】
 * 1. Servlet Filter，拦截所有请求，包装为 XssHttpServletRequestWrapper
 * 2. 同时通过 JacksonConfig 注册 XssStringJsonDeserializer，处理 JSON 请求体
 * 3. 双重防护：表单参数 + JSON 请求体都清洗
 *
 * 【Filter 执行流程】
 * 1. 请求进入 → XssFilter.doFilter
 * 2. 包装原始 request 为 XssHttpServletRequestWrapper
 * 3. 传递包装后的 request 给后续 Filter 和 Servlet
 * 4. Controller 获取参数时，已经是清洗后的值
 *
 * 【为什么用 @Configuration 而不是 @Component】
 * - @Configuration 内部的 @Bean 方法会被 Spring 代理，保证单例
 * - FilterRegistrationBean 用于配置 Filter 的注册（URL 模式、顺序）
 * - 这种方式比 @Component + @WebFilter 更可控
 *
 * 【排除路径说明】
 * - 富文本编辑器（如文章内容）需要保留 HTML 标签，需排除
 * - 通过 urlPatterns 排除特定路径，或通过配置类动态控制
 *
 * 【使用方式】
 * - 业务服务引入 common-security 依赖
 * - Spring Boot 自动扫描 @Configuration，注册 XssFilter
 * - 默认拦截 /* 所有请求
 *
 * 【面试要点】
 * - Filter vs Interceptor：Filter 在 Servlet 层，Interceptor 在 Spring 层
 * - Filter 适合：编码转换、XSS 防护、CORS、日志
 * - Interceptor 适合：登录校验、权限控制、审计
 *
 * @author litisle
 */
@Slf4j
@Configuration
public class XssFilter {

    /**
     * 注册 XSS 过滤器
     *
     * 【FilterRegistrationBean 说明】
     * - Spring Boot 提供的 Filter 注册工具
     * - 可配置 URL 模式、执行顺序、是否启用
     * - 比 @WebFilter 更灵活，无需 @ServletComponentScan
     *
     * @return Filter 注册 Bean
     */
    @Bean
    public FilterRegistrationBean<Filter> xssFilterRegistration() {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
        // 注册实际的 Filter 逻辑
        registration.setFilter((ServletRequest request, ServletResponse response, FilterChain chain)
                -> doXssFilter(request, response, chain));
        // 拦截所有请求
        registration.addUrlPatterns("/*");
        // 过滤器顺序（值越小越先执行）
        registration.setOrder(1);
        // 过滤器名称
        registration.setName("xssFilter");
        log.info("XSS 过滤器已注册，拦截路径: /*");
        return registration;
    }

    /**
     * 实际的 XSS 过滤逻辑
     *
     * @param request  原始请求
     * @param response 响应
     * @param chain    过滤器链
     */
    private void doXssFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        // 仅处理 HttpServletRequest
        if (request instanceof HttpServletRequest httpRequest) {
            // 包装为 XSS 安全的 request
            XssHttpServletRequestWrapper wrappedRequest = new XssHttpServletRequestWrapper(httpRequest);
            chain.doFilter(wrappedRequest, response);
        } else {
            chain.doFilter(request, response);
        }
    }
}