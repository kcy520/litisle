package com.litisle.commonsecurity.resolver;



import com.litisle.commoncore.resolver.UserIdResolver;
import com.litisle.commonsecurity.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 基于安全上下文的 UserIdResolver 实现
 *
 * 【设计说明】
 * 1. 实现 common-core 的 UserIdResolver 接口
 * 2. 从 SecurityContextHolder（ThreadLocal）获取当前 userId
 * 3. 注册为 Spring Bean，供 common-redis 的 IdempotentAspect 自动注入
 *
 * 【依赖倒置的体现】
 * - common-redis 定义了"需要 userId"的需求（通过 UserIdResolver 接口）
 * - common-security 提供了"我能提供 userId"的实现
 * - 两者都依赖 common-core 的接口，互不依赖
 * - Spring 容器负责装配：业务服务同时引入两者时，自动注入此实现
 *
 * 【为什么不放在 common-redis】
 * - SecurityContextHolder 是 common-security 的核心类
 * - 如果放在 common-redis，common-redis 就要依赖 common-security，形成循环
 * - 放在 common-security 最合理：实现类与被实现的类在同一模块
 *
 * 【自动装配流程】
 * 1. 业务服务引入 common-redis 和 common-security
 * 2. CommonSecurityAutoConfiguration 注册 SecurityContextUserIdResolver（@Bean）
 * 3. IdempotentAspect 的 @Autowired(required=false) 注入此实现
 * 4. 切面调用 resolveUserId() → 实际调用 SecurityContextHolder.getUserId()
 *
 * @author litisle
 */
public class SecurityContextUserIdResolver implements UserIdResolver {

    /**
     * 从安全上下文获取当前用户ID
     *
     * @return 用户ID，未登录或无上下文时返回 null
     */
    @Override
    public Long getUserId() {
        return SecurityContextHolder.getUserId();
    }
}