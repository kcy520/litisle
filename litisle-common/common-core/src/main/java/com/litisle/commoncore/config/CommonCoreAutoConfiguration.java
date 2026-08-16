package com.litisle.commoncore.config;

import com.litisle.commoncore.exception.GlobalExceptionHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * common-core 核心自动配置
 *
 * 【设计说明】
 * 集中注册所有 Bean，确保作为库模块时 Bean 一定被注册。
 * 消费方只需引入 common-core 依赖 + AutoConfiguration.imports，无需额外配置组件扫描路径。
 *
 * 注册内容：
 * 1. GlobalExceptionHandler — @RestControllerAdvice 全局异常处理器（含 @Component）
 * 2. JacksonConfig 和 MyBatisPlusConfig 直接在 AutoConfiguration.imports 中注册
 *    （它们是 @Configuration 类，通过 imports 文件自动发现）
 *
 * @author litisle
 */
@Configuration
public class CommonCoreAutoConfiguration {

    /**
     * 全局异常处理器
     *
     * 【说明】
     * GlobalExceptionHandler 标注了 @RestControllerAdvice（含 @Component 元注解），
     * 但库模块中的 @Component 类不会被消费方服务的组件扫描发现，
     * 因此需要显式注册为 @Bean。
     *
     * @ConditionalOnMissingBean 允许消费方定义自己的全局异常处理器覆盖默认行为。
     */
    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}