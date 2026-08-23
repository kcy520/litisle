package com.litisle.commonsecurity.config;

import com.litisle.commonsecurity.anonymous.AnonymousAccessHandler;
import com.litisle.commonsecurity.anonymous.AnonymousPathScanner;
import com.litisle.commonsecurity.anonymous.AnonymousProperties;
import com.litisle.commonsecurity.audit.AuditLogAspect;
import com.litisle.commonsecurity.feign.FeignAuthInterceptor;
import com.litisle.commonsecurity.trace.TraceIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * common-security 核心自动配置
 *
 * 【设计说明】
 * 集中注册所有 Bean，替代 @Component 组件扫描，确保作为库模块时 Bean 一定被注册。
 * 消费方只需引入 common-security 依赖 + AutoConfiguration.imports，无需额外配置组件扫描路径。
 *
 * @author litisle
 */
@Configuration
@EnableConfigurationProperties(AnonymousProperties.class)
public class CommonSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FeignAuthInterceptor feignAuthInterceptor() {
        return new FeignAuthInterceptor();
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditLogAspect auditLogAspect(ObjectMapper objectMapper) {
        return new AuditLogAspect(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public AnonymousAccessHandler anonymousAccessHandler(AnonymousProperties anonymousProperties) {
        return new AnonymousAccessHandler(anonymousProperties);
    }

    /**
     * 扫描 @Anonymous 注解路径并注册到匿名处理器
     */
    @Bean
    @ConditionalOnMissingBean
    public AnonymousPathScanner anonymousPathScanner(AnonymousAccessHandler anonymousAccessHandler) {
        return new AnonymousPathScanner(anonymousAccessHandler);
    }

    /**
     * 注册 Trace ID 过滤器（链路追踪承接）
     * 从请求 Header 读取 X-Trace-Id 写入 MDC，供 FeignAuthInterceptor 透传下游
     * order 最高优先级，保证最早执行
     */
    @Bean
    @ConditionalOnMissingBean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration() {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(new TraceIdFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}