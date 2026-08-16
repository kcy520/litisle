package com.litisle.litislegateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关 JWT 配置属性
 *
 * 【设计说明】
 * 1. 绑定网关的 yml 配置，提供匿名白名单路径列表
 * 2. 网关使用 WebFlux，无法复用 common-security 的 AnonymousPathScanner（依赖 Spring MVC）
 * 3. 白名单路径通过 yml 配置，支持 Ant 风格通配符
 *
 * 【yml 配置示例】
 * litisle:
 *   security:
 *     anonymous:
 *       paths:
 *         - /api/auth/login
 *         - /api/auth/register
 *
 * @author litisle
 */
@Data
@Component
@ConfigurationProperties(prefix = "litisle.security.anonymous")
public class GatewayJwtProperties {

    /**
     * 匿名访问路径列表（支持 Ant 风格通配符）
     * 网关层白名单，这些路径跳过 JWT 校验
     */
    private List<String> paths = new ArrayList<>();
}