package com.litisle.commonsecurity.anonymous;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 匿名访问配置类（yml + JavaBean 绑定）
 *
 * 【设计说明】
 * 1. 使用 @ConfigurationProperties 将 yml 配置绑定到 JavaBean
 * 2. 相比 @Value 的优势：
 *    - 支持松散绑定（kebab-case 与 camelCase 互转）
 *    - 支持嵌套对象、集合、Map
 *    - 支持 JSR303 校验（@Validated）
 *    - 配置集中管理，便于维护
 * 3. 网关和业务服务都可引用此配置，统一匿名白名单
 *
 * 【yml 配置示例】
 * litisle:
 *   security:
 *     anonymous:
 *       paths:
 *         - /api/auth/login
 *         - /api/auth/register
 *         - /api/auth/captcha
 *         - /api/content/article/list
 *         - /api/content/article/{id}
 *
 * 【面试要点】
 * - @ConfigurationProperties vs @Value：前者支持复杂结构、校验、刷新；后者仅支持简单值
 * - prefix 指定配置前缀，松散绑定 litisle.security.anonymous.paths → anonymous.paths
 *
 * @author litisle
 */
@Data
@ConfigurationProperties(prefix = "litisle.security.anonymous")
public class AnonymousProperties {

    /**
     * 匿名访问路径列表（支持 Ant 风格通配符）
     *
     * 【通配符说明】
     * - ?：匹配单个字符
     * - *：匹配 0 个或多个字符（不含 /）
     * - **：匹配 0 个或多个路径段（含 /）
     *
     * 【示例】
     * - /api/auth/login：精确匹配
     * - /api/content/article/**：匹配该路径下所有子路径
     * - /api/ * /list：匹配所有模块的 list 接口
     */
    private List<String> paths = new ArrayList<>();
}