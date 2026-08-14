package com.litisle.commonsecurity.audit;



import com.litisle.commoncore.enums.AuditModule;
import com.litisle.commoncore.enums.AuditOperation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计日志注解
 * 
 * 【设计说明】
 * 1. 标注在需要审计的方法上，自动记录操作日志
 * 2. 搭配 AuditLogAspect 切面，自动收集操作人、参数、耗时等信息
 * 3. 适用于敏感操作：删除、修改、登录、提现等
 * 
 * 【使用场景】
 * - 用户注销账号
 * - 管理员封禁用户
 * - OAuth 解绑
 * - 修改密码
 * - 提现申请
 * - 商品上下架
 * 
 * 【使用示例】
 * @AuditLog(module = "用户管理", operation = "封禁用户")
 * public Result<Void> banUser(Long userId) {
 *     // 业务逻辑
 * }
 * 
 * @AuditLog(module = "OAuth", operation = "解绑第三方账号", saveRequestData = true)
 * public Result<Void> unbindOAuth(Long bindingId) {
 *     // 业务逻辑
 * }
 * 
 * @author litisle
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {

    /**
     * 模块名称（如 "用户管理"、"OAuth"、"支付"）
     */
//    String module() default "";
    AuditModule moudle();

    /**
     * 操作类型（如 "封禁用户"、"解绑账号"、"提现"）
     */
//    String operation() default "";
    AuditOperation operation();


    /**
     * 是否记录请求参数
     * 【说明】敏感参数（如密码）应设为 false
     */
    boolean saveRequestData() default true;

    /**
     * 是否记录响应结果
     * 【说明】响应数据可能很大，默认不记录
     */
    boolean saveResponseData() default false;

    /**
     * 是否记录异常堆栈（方法抛出异常时）
     */
    boolean saveException() default true;
}