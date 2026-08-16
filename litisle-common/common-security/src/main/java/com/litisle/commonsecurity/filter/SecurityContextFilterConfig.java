package com.litisle.commonsecurity.filter;

import com.litisle.commonsecurity.anonymous.AnonymousAccessHandler;
import com.litisle.commonsecurity.anonymous.AnonymousProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.Collections;

/**
 * SecurityContextFilter 注册配置
 *
 * 【设计说明】
 * 1. 使用 @Configuration + FilterRegistrationBean 注册，与 XssFilter 模式一致
 * 2. 拦截 /api/* 路径，排除 actuator、静态资源等
 * 3. 设置最高优先级，在业务切面之前执行
 * 4. 与 XssFilter 的顺序关系：
 *    - SecurityContextFilter (Ordered.HIGHEST_PRECEDENCE + 10) 先执行
 *    - XssFilter (order=1) 后执行
 *    - 顺序：认证上下文 → XSS 清洗 → 业务逻辑
 *
 * 【为什么独立成文件？】
 * - SecurityContextFilter 是普通类（非 @Configuration），
 *   内部 @Configuration 静态类不会被 Spring 组件扫描发现
 * - 独立文件后，@Configuration 能被 Spring 正常扫描并处理
 *
 * @author litisle
 */
@Configuration
@EnableConfigurationProperties(AnonymousProperties.class)
public class SecurityContextFilterConfig {

    /**
     * 创建 AnonymousAccessHandler Bean
     * <p>
     * 【说明】显式 @Bean 注册，替代 @Component 扫描，使 IDE 能正确识别 Bean 定义
     */
    @Bean
    public AnonymousAccessHandler anonymousAccessHandler(AnonymousProperties anonymousProperties) {
        return new AnonymousAccessHandler(anonymousProperties);
    }

    /**
     * 用 Java 代码把 SecurityContextFilter 装进 Web 容器并精确控制拦截路径和顺序”的入口。
     * @param anonymousAccessHandler
     * @return
     */
    @Bean
    public FilterRegistrationBean<SecurityContextFilter> securityContextFilterRegistration(
            AnonymousAccessHandler anonymousAccessHandler) {                         // 从容器中注入匿名处理器（白名单逻辑）
        FilterRegistrationBean<SecurityContextFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new SecurityContextFilter(anonymousAccessHandler));     // 手动 new Filter，把匿名规则传进去（不走组件扫描）
        //todo 所有“代表用户身份的业务接口” → 必须 /api 开头
        registration.addUrlPatterns("/api/*");                                        // 只拦截业务接口，不碰 actuator/静态资源
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);                       // 优先级极高，保证在 XSS/业务切面之前构建安全上下文
        registration.setName("securityContextFilter");                                // Filter 名称，便于日志/排查
        return registration;                                                          // 交给 Servlet 容器注册
    }
}