package com.litisle.commonsecurity.permission;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commonsecurity.role.RequireRole;
import com.litisle.commonsecurity.context.SecurityContext;
import com.litisle.commonsecurity.context.SecurityContextHolder;
import com.litisle.commonsecurity.enums.PermissionEnum;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

/**
 * 权限校验切面（细粒度权限）
 *
 * 【设计说明】
 * 1. 拦截标注 @RequirePermission 的方法，在方法执行前校验细粒度权限
 * 2. 校验失败抛出 BizException(FORBIDDEN)，由全局异常处理器统一处理
 * 3. 与 RoleCheckAspect 的区别：
 *    - RoleCheckAspect：校验粗粒度角色（ADMIN、AUTHOR、REVIEWER）
 *    - PermissionCheckAspect：校验细粒度权限（user:delete、article:publish）
 * 4. 权限来源：SecurityContext.permissions（由 JwtAuthenticationFilter 解析 JWT 后写入）
 *
 * 【权限模型】
 * - 角色（粗粒度）：ADMIN、AUTHOR、REVIEWER
 * - 权限（细粒度）：user:read、user:delete、article:publish
 * - 关系：一个角色拥有多个权限（角色-权限映射存于 DB，由 auth 服务管理）
 * - 权限码格式：资源:动作（如 user:delete）
 *
 * 【执行流程】
 * 1. 用户调用 Controller 方法（标注 @RequirePermission）
 * 2. 切面读取当前用户的权限集合
 * 3. 根据逻辑关系（OR/AND）校验是否拥有所需权限
 * 4. 校验通过 → 执行目标方法
 * 5. 校验失败 → 抛出 BizException(FORBIDDEN)
 *
 * @author litisle
 */
@Slf4j
@Aspect
public class PermissionCheckAspect {

    /**
     * 前置通知：校验权限
     *
     * 【切点表达式说明】
     * @annotation(requirePermission) 匹配标注 @RequirePermission 的方法，并绑定注解对象
     *
     * @param joinPoint          连接点
     * @param requirePermission  注解对象（包含权限列表、逻辑关系、提示消息）
     */
    @Before("@annotation(requirePermission)")
    public void checkPermission(JoinPoint joinPoint, RequirePermission requirePermission) {
        // 1. 获取当前用户的安全上下文
        SecurityContext context = SecurityContextHolder.getContext();

        // 2. 未登录校验
        if (context == null || !context.isAuthenticated()) {
            log.warn("未登录访问需要权限的接口: method={}", getMethodName(joinPoint));
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }

        // 3. 获取当前用户的权限集合
        Set<String> userPermissions = context.getPermissions();
        if (userPermissions == null || userPermissions.isEmpty()) {
            log.warn("用户无权限: userId={}, method={}", context.getUserId(), getMethodName(joinPoint));
            throw new BizException(ErrorCode.FORBIDDEN, requirePermission.message());
        }

        // 4. 获取注解配置的权限列表-----字符串版本
//        String[] requiredPermissions = requirePermission.value();
//        RequireRole.Logical logical = requirePermission.logical();
       // 5. 根据逻辑关系校验
//        boolean hasPermission;
//        if (logical == RequireRole.Logical.OR) {
//            // OR：满足任意一个权限即可
//            hasPermission = Arrays.stream(requiredPermissions)
//                    .anyMatch(userPermissions::contains);
//        } else {
//            // AND：必须同时拥有所有权限
//            hasPermission = Arrays.stream(requiredPermissions)
//                    .allMatch(userPermissions::contains);
//        }


        // 4. 获取注解配置的权限（纯枚举）
        PermissionEnum[] requiredPerms = requirePermission.perms();
        // ✅ 兜底：没配权限直接拒绝
        if (requiredPerms.length == 0) {
            log.error("注解未配置权限: method={}", getMethodName(joinPoint));
            throw new BizException(ErrorCode.FORBIDDEN, "接口未声明权限点");
        }
        RequireRole.Logical logical = requirePermission.logical();


        // 5. 校验（基于 enum）
        boolean hasPermission;
        if (logical == RequireRole.Logical.OR) {
            hasPermission = Arrays.stream(requiredPerms)
                    .anyMatch(p -> userPermissions.contains(p.getCode()));
        } else {
            hasPermission = Arrays.stream(requiredPerms)
                    .allMatch(p -> userPermissions.contains(p.getCode()));
        }


        // 6. 校验失败抛出异常
        if (!hasPermission) {
            log.warn("权限校验失败: userId={}, userPermissions={}, required={}, logical={}",
                    context.getUserId(),
                    userPermissions,
                    Arrays.toString(Arrays.stream(requiredPerms)
                            .map(PermissionEnum::getCode)
                            .toArray()),
                    logical);

            throw new BizException(ErrorCode.FORBIDDEN, requirePermission.message());
        }

        log.debug("权限校验通过: userId={}, method={}", context.getUserId(), getMethodName(joinPoint));
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