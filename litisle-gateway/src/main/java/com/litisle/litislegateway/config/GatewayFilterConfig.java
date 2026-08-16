package com.litisle.litislegateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 网关过滤器配置类
 *
 * 【设计说明】
 * 1. 将 GatewayJwtProperties 中的匿名白名单路径列表暴露为 Bean
 * 2. JwtAuthGlobalFilter 通过构造器注入此 List，无需直接依赖 @ConfigurationProperties
 * 3. 遵循"单一职责"原则：配置类负责绑定配置，过滤器负责认证逻辑
 *
 * @author litisle
 */
@Configuration
public class GatewayFilterConfig {

    /**
     * 将 JWT 配置中的匿名路径列表暴露为 Bean
     *
     * @param properties 网关 JWT 配置属性
     * @return 匿名路径列表
     */
    @Bean
    public List<String> anonymousPaths(GatewayJwtProperties properties) {
        return properties.getPaths();
    }
}