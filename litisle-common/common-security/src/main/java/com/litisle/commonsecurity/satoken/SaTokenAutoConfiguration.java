package com.litisle.commonsecurity.satoken;

import cn.dev33.satoken.listener.SaTokenListener;
import cn.dev33.satoken.stp.StpInterface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litisle.commoncore.resolver.UserIdResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sa-Token 自动配置
 *
 * 【设计说明】
 * 1. 集中注册 Sa-Token 相关的所有 Bean，替代 @Component 组件扫描
 * 2. 通过 AutoConfiguration.imports 文件注册，确保作为库模块时 Bean 一定被 Spring 发现
 * 3. 所有 Bean 使用 @ConditionalOnMissingBean，允许业务服务覆盖默认实现
 *
 * 【注册的 Bean】
 * - StpInterfaceImpl：Sa-Token 角色/权限数据源（从 Session 读取）
 * - SaTokenUserIdResolver：UserIdResolver 实现（供 common-redis 幂等/限流切面使用）
 * - SaTokenEventListener：Sa-Token 会话事件监听器（登录/登出/踢人/封禁审计日志）
 *
 * 【与 CommonSecurityAutoConfiguration 的关系】
 * - CommonSecurityAutoConfiguration：注册纯安全组件（Feign 拦截器、审计切面、匿名扫描器）
 * - SaTokenAutoConfiguration：注册 Sa-Token 框架集成组件（StpInterface、UserIdResolver）
 * - 两者独立，职责清晰，符合单一职责原则
 *
 * @author litisle
 */
@Configuration
public class SaTokenAutoConfiguration {

    /**
     * Sa-Token 角色/权限数据源
     *
     * 【说明】
     * - Sa-Token 框架通过 StpInterface 接口获取角色和权限列表
     * - 当前实现从 Sa-Token Session 中读取（登录时由 litisle-auth 写入）
     * - 业务服务可通过 @ConditionalOnMissingBean 覆盖，改为从数据库直接查询
     */
    @Bean
    @ConditionalOnMissingBean(StpInterface.class)
    public StpInterfaceImpl stpInterface() {
        return new StpInterfaceImpl();
    }

    /**
     * UserIdResolver 实现（Sa-Token 版）
     *
     * 【说明】
     * - 供 common-redis 的 IdempotentAspect / RateLimiterAspect 使用
     * - 通过 ObjectProvider 注入，未引入 common-security 时 resolver 为 null
     * - 业务服务可通过 @ConditionalOnMissingBean 覆盖（如改为从 Header 直接读取）
     */
    @Bean
    @ConditionalOnMissingBean(UserIdResolver.class)
    public SaTokenUserIdResolver saTokenUserIdResolver() {
        return new SaTokenUserIdResolver();
    }

    /**
     * Sa-Token 会话事件监听器
     *
     * 【说明】
     * - 监听登录/登出/踢人/顶人/封禁/解封等认证事件，输出统一格式审计日志
     * - 与 AuditLogAspect 互补：Aspect 记录业务操作，Listener 记录认证事件
     * - 业务服务可通过 @ConditionalOnMissingBean 覆盖，改为发送 MQ 或写入数据库
     */
    @Bean
    @ConditionalOnMissingBean(SaTokenListener.class)
    public SaTokenEventListener saTokenEventListener(ObjectMapper objectMapper) {
        return new SaTokenEventListener(objectMapper);
    }
}