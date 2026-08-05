package com.litisle.commonsecurity.anonymous;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 匿名访问注解
 *
 * 【设计说明】
 * 1. 标注在 Controller 方法或类上，表示该接口允许匿名访问（无需 JWT）
 * 2. 网关扫描该注解，将对应路径加入白名单，跳过 JWT 校验
 * 3. 业务服务在 Filter 中检测到匿名请求，构建匿名 SecurityContext（userId=null, roles=[ANONYMOUS]）
 *
 * 【两种控制方式】
 * - 注解方式（推荐，声明式）：@Anonymous 标注在方法上
 * - 配置方式（灵活，运维可控）：application.yml 中配置 litisle.security.anonymous.paths
 * 两种方式合并为最终白名单，互为补充
 *
 * 【与 Spring Security 的对比】
 * - Spring Security 的 permitAll() 配置繁琐，且依赖 HttpSecurity 链
 * - 本注解更轻量，由网关统一处理，业务服务零配置
 *
 * 【使用示例】
 * // 示例1：方法级匿名访问
 * @Anonymous
 * @GetMapping("/public/article/{id}")
 * public Result<ArticleVO> getArticle(@PathVariable Long id) { ... }
 *
 * // 示例2：类级匿名访问（整个 Controller 都匿名）
 * @Anonymous
 * @RestController
 * @RequestMapping("/public")
 * public class PublicController { ... }
 *
 * @author litisle
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Anonymous {

    /**
     * 备注（用于日志和文档，不影响逻辑）
     */
    String value() default "";
}