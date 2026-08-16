package com.litisle.commonsecurity.aspect;


import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commonsecurity.annotation.RequireRole;
import com.litisle.commonsecurity.context.SecurityContext;
import com.litisle.commonsecurity.context.SecurityContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

/**
 * 角色校验切面
 * 
 * 【设计说明】
 * 1. 拦截标注 @RequireRole 的方法，在方法执行前校验角色
 * 2. 校验失败抛出 BizException，由全局异常处理器统一处理
 * 3. 使用 @Before 通知，在方法执行前完成校验
 * 
 * 【AOP 核心概念】
 * - @Aspect：声明切面类
 * - @Before：前置通知，在目标方法执行前执行
 * - @Pointcut：切点（这里通过注解直接定义切点）
 * - JoinPoint：连接点，可以获取目标方法信息
 * 
 * 【执行流程】
 * 1. 用户调用 Controller 方法
 * 2. Spring AOP 拦截方法（如果标注了 @RequireRole）
 * 3. 切面从 SecurityContextHolder 读取当前用户角色
 * 4. 校验角色是否满足要求
 * 5. 校验通过 → 执行目标方法
 * 6. 校验失败 → 抛出 BizException
 * 
 * @author litisle
 */
@Slf4j
@Aspect
public class RoleCheckAspect {

    /**
     * 前置通知：校验角色
     * 
     * 【切点表达式说明】
     * @annotation(requireRole) 表示拦截所有标注 @RequireRole 的方法
     * 并将注解对象作为参数传入
     * 
     * @param joinPoint    连接点（可获取目标方法信息）
     * @param requireRole  注解对象（包含角色列表、逻辑关系、提示消息）
     */
    @Before("@annotation(requireRole)")
    public void checkRole(JoinPoint joinPoint, RequireRole requireRole) {
        // 1. 获取当前用户的安全上下文
        SecurityContext context = SecurityContextHolder.getContext();

        // 2. 未登录校验
        if (context == null || !context.isAuthenticated()) {
            log.warn("未登录访问需要权限的接口: method={}", getMethodName(joinPoint));
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }

        // 3. 获取当前用户的角色集合
        Set<String> userRoles = context.getRoles();
        if (userRoles == null || userRoles.isEmpty()) {
            log.warn("用户无角色: userId={}, method={}", context.getUserId(), getMethodName(joinPoint));
            throw new BizException(ErrorCode.FORBIDDEN, requireRole.message());
        }

        // 4. 获取注解配置的角色列表
        String[] requiredRoles = requireRole.value();
        RequireRole.Logical logical = requireRole.logical();

        // 5. 根据逻辑关系校验
        boolean hasPermission;
        if (logical == RequireRole.Logical.OR) {
            // OR：满足任意一个角色即可
            hasPermission = Arrays.stream(requiredRoles)
                    .anyMatch(userRoles::contains);
        } else {
            // AND：必须同时拥有所有角色
            hasPermission = Arrays.stream(requiredRoles)
                    .allMatch(userRoles::contains);
        }

        // 6. 校验失败抛出异常
        if (!hasPermission) {
            log.warn("角色校验失败: userId={}, userRoles={}, requiredRoles={}, logical={}",
                    context.getUserId(), userRoles, Arrays.toString(requiredRoles), logical);
            throw new BizException(ErrorCode.FORBIDDEN, requireRole.message());
        }

        log.debug("角色校验通过: userId={}, method={}", context.getUserId(), getMethodName(joinPoint));
    }

    /**
     * 获取目标方法名（用于日志记录）
     * 
     * @param joinPoint 连接点
     * @return 方法名（格式：类名.方法名）
     */
    private String getMethodName(JoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }
}