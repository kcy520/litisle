package com.litisle.commonsecurity.annotation;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 权限校验注解（预留，未来扩展细粒度权限控制）
 * 
 * 【设计说明】
 * 1. 当前项目使用角色即可满足需求，权限注解为预留扩展
 * 2. 未来如需细粒度权限控制（如 user:delete、article:publish），可启用此注解
 * 3. 权限与角色的区别：
 *    - 角色：粗粒度（ADMIN、AUTHOR）
 *    - 权限：细粒度（user:read、user:write、article:delete）
 *    - 一个角色可以拥有多个权限
 * 
 * 【使用示例（未来扩展）】
 *  //user-资源 delete-行为 权限的存储规范格式为：资源:动作
 *  因为权限码保护的是接口，接口在 Controller 里，Controller 的名字就是最好的资源名。 UserController        → user
 *
 * @RequirePermission("user:delete")
 * public Result<Void> deleteUser(Long userId) { ... }
 * 
 * @RequirePermission({"article:read", "article:write"})
 * public Result<Void> editArticle(ArticleDTO dto) { ... }
 * 
 * @author litisle
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    /**
     * 权限编码列表
     * 【说明】如 "user:delete"、"article:publish"
     */
    String[] value();

    /**
     * 逻辑关系（默认 OR）
     */
    RequireRole.Logical logical() default RequireRole.Logical.OR;

    /**
     * 提示消息
     */
    String message() default "权限不足";
}