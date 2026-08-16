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
 * 1. 绑定网关的 yml 配置，提供 JWT 相关配置及匿名白名单路径列表
 * 2. 网关使用 WebFlux，无法复用 common-security 的 AnonymousPathScanner（依赖 Spring MVC）
 * 3. 白名单路径通过 yml 配置，支持 Ant 风格通配符
 *
 * 【为何不与 AnonymousProperties 共用 prefix】
 * - AnonymousProperties 在 common-security 中，prefix 为 litisle.security.anonymous
 * - 网关是独立部署的服务，有自己独立的配置空间
 * - 若共用 prefix，双方配置会互相覆盖，导致启动时绑定冲突
 * - 故使用独立 prefix：litisle.gateway.jwt
 *
 * 【yml 配置示例】
 * litisle:
 *   gateway:
 *     jwt:
 *       paths:
 *         - /api/auth/login
 *         - /api/auth/register
 *
 * @author litisle
 */
@Data
@Component
@ConfigurationProperties(prefix = "litisle.gateway.jwt")
public class GatewayJwtProperties {
    // 问题：之前与AnonymousProperties类， 两个 @ConfigurationProperties 绑定同一个 prefix，启动时它们会互相覆盖或冲突，导致其中一个读取不到正确的配置。所以在此修改次类prefix

    /**
     * 匿名访问路径列表（支持 Ant 风格通配符）
     * 网关层白名单，这些路径跳过 JWT 校验
     */
    private List<String> paths = new ArrayList<>();
}