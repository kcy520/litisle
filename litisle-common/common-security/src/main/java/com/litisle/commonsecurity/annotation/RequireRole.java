package com.litisle.commonsecurity.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// 用自定义注解，是为了让业务代码依赖“我的安全模型”，而不是依赖 Spring Security。**

/**
 * 角色校验注解
 * 
 * 【设计说明】
 * 1. 方法级角色校验注解，搭配 RoleCheckAspect 切面使用
 * 2. 支持指定多个角色，满足其一即可访问（OR 关系）
 * 3. 比Spring Security 的 @PreAuthorize 更轻量
 * 
 * 【使用示例】
 * // 示例1：只有 ADMIN 才能访问
 * @RequireRole("ADMIN")
 * public Result<Void> deleteUser(Long userId) { ... }
 * 
 * // 示例2：ADMIN 或 SUPER_ADMIN 都能访问
 * @RequireRole({"ADMIN", "SUPER_ADMIN"})
 * public Result<Void> banUser(Long userId) { ... }
 * 
 * // 示例3：需要同时拥有 ADMIN 和 REVIEWER（AND 关系，logical=AND）
 * @RequireRole(value = {"ADMIN", "REVIEWER"}, logical = Logical.AND)
 * public Result<Void> reviewAppeal(Long appealId) { ... }
 * 
 * 【元注解说明】
 * - @Target(ElementType.METHOD)：注解只能用在方法上
 * - @Retention(RetentionPolicy.RUNTIME)：运行时保留（反射可读取）
 * 
 * @author litisle
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    /**
     * 角色编码列表
     * 【说明】如 "ADMIN"、"AUTHOR"、"REVIEWER"
     */
    String[] value();

    /**
     * 逻辑关系（默认 OR）
     * - OR：满足任意一个角色即可
     * - AND：必须同时拥有所有角色
     */
    Logical logical() default Logical.OR;

    /**
     * 提示消息（校验失败时返回）
     */
    String message() default "权限不足";

    /**
     * 角色逻辑关系枚举
     */
    enum Logical {
        /**
         * 满足任意一个角色
         */
        OR,

        /**
         * 必须同时拥有所有角色
         */
        AND
    }
}