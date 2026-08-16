package com.litisle.commonsecurity.config;

import com.litisle.commonsecurity.anonymous.AnonymousAccessHandler;
import com.litisle.commonsecurity.anonymous.AnonymousPathScanner;
import com.litisle.commonsecurity.aspect.PermissionCheckAspect;
import com.litisle.commonsecurity.aspect.RoleCheckAspect;
import com.litisle.commonsecurity.audit.AuditLogAspect;
import com.litisle.commonsecurity.feign.FeignAuthInterceptor;
import com.litisle.commonsecurity.login.LoginAspect;
import com.litisle.commonsecurity.resolver.SecurityContextUserIdResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
public class CommonSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FeignAuthInterceptor feignAuthInterceptor() {
        return new FeignAuthInterceptor();
    }

    @Bean
    @ConditionalOnMissingBean
    public PermissionCheckAspect permissionCheckAspect() {
        return new PermissionCheckAspect();
    }

    @Bean
    @ConditionalOnMissingBean
    public RoleCheckAspect roleCheckAspect() {
        return new RoleCheckAspect();
    }

    @Bean
    @ConditionalOnMissingBean
    public LoginAspect loginAspect() {
        return new LoginAspect();
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditLogAspect auditLogAspect(ObjectMapper objectMapper) {
        return new AuditLogAspect(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityContextUserIdResolver securityContextUserIdResolver() {
        return new SecurityContextUserIdResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public AnonymousPathScanner anonymousPathScanner(AnonymousAccessHandler anonymousAccessHandler) {
        return new AnonymousPathScanner(anonymousAccessHandler);
    }
}