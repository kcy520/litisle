package com.litisle.commonsecurity.login;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 登录校验注解
 *
 * 支持方法和类级别：
 * 【设计说明】
 * 1. 标注在 Controller 方法或类上，表示该接口需要登录后才能访问
 * 2. 搭配 LoginAspect 切面使用，在方法执行前校验登录状态
 * 3. 与 @RequireRole、@Anonymous 的职责区分：
 *    - @Login：仅校验是否登录，不校验角色（如获取个人信息、修改头像）
 *    - @RequireRole：校验登录 + 角色权限（如管理员才能删除用户）
 *    - @Anonymous：完全跳过登录校验（如公开文章浏览）
 * 4. 适用场景：仅需登录无需特定角色的接口，避免误配角色导致权限放大
 *
 * 【使用示例】
 * // 示例1：方法级登录校验
 * @Login
 * @GetMapping("/user/profile")
 * public Result<UserVO> getProfile() { ... }
 *
 * // 示例2：类级登录校验（整个 Controller 都需登录）
 * @Login
 * @RestController
 * @RequestMapping("/api/user")
 * public class UserController { ... }
 *
 * // 示例3：自定义提示消息
 * @Login(message = "请先登录后再操作")
 * @PostMapping("/comment/post")
 * public Result<Void> postComment(@RequestBody CommentDTO dto) { ... }
 *
 * 【元注解说明】
 * - @Target({ElementType.METHOD, ElementType.TYPE})：支持方法和类级别
 * - @Retention(RetentionPolicy.RUNTIME)：运行时保留（反射可读取）
 *
 * @author litisle
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Login {

    /**
     * 提示消息（校验失败时返回）
     */
    String message() default "未登录或登录已过期";
}