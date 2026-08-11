package com.litisle.commonsecurity.aspect;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commonsecurity.annotation.Anonymous;
import com.litisle.commonsecurity.annotation.Login;
import com.litisle.commonsecurity.context.SecurityContext;
import com.litisle.commonsecurity.context.SecurityContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 登录校验切面
 *
 * - 方法级 + 类级双切点 ： @annotation(login) 处理方法级， @within(login) && !@annotation(Login) 处理类级（避免重复校验）
 * - @Anonymous 优先 ：方法标注 @Anonymous 时跳过登录校验（匿名访问由网关白名单控制，AOP 层不重复拦截）
 *
 * 【设计说明】
 * 1. 拦截标注 @Login 的方法或类，在方法执行前校验登录状态
 * 2. 校验失败抛出 BizException(UNAUTHORIZED)，由全局异常处理器统一处理
 * 3. 支持方法级和类级注解：
 *    - 方法级：仅校验标注 @Login 的方法
 *    - 类级：校验该类下所有方法（方法级 @Login 已单独处理，避免重复校验）
 * 4. 与 @Anonymous 互斥：方法标注 @Anonymous 时跳过登录校验
 *    （匿名访问由网关白名单 + AnonymousPathScanner 控制，AOP 层不重复拦截）
 *
 * 【执行流程】
 * 1. 用户调用 Controller 方法
 * 2. Spring AOP 拦截（方法或类标注了 @Login）
 * 3. 切面从 SecurityContextHolder 读取当前用户上下文
 * 4. 判断是否已登录（userId != null）
 * 5. 未登录 → 抛出 BizException(UNAUTHORIZED)
 * 6. 已登录 → 放行执行目标方法
 *
 * 【为什么需要 @Login？】
 * - @RequireRole 校验角色，隐含了登录校验，但语义偏重
 * - 部分接口仅需登录无需角色（如获取个人信息、发布评论）
 * - 使用 @Login 语义更清晰，避免误配角色导致权限放大或缩小
 *
 * @author litisle
 */
@Slf4j
@Aspect
@Component
public class LoginAspect {

    /**
     * 方法级登录校验：拦截标注 @Login 的方法
     *
     * 【切点表达式说明】
     * @annotation(login) 匹配方法上标注 @Login 的方法，并绑定注解对象
     *
     * @param joinPoint 连接点
     * @param login     注解对象（包含提示消息）
     */
    @Before("@annotation(login)")
    public void checkMethodLogin(JoinPoint joinPoint, Login login) {
        // 方法级 @Anonymous 优先，跳过登录校验
        if (isAnonymous(joinPoint)) {
            return;
        }
        checkLogin(joinPoint, login.message());
    }

    /**
     * 类级登录校验：拦截标注 @Login 的类下所有方法
     *
     * 【切点表达式说明】
     * @within(login) 匹配类上标注 @Login 的所有方法
     * !@annotation(Login) 排除方法级 @Login（已由 checkMethodLogin 处理，避免重复校验）
     *
     * @param joinPoint 连接点
     * @param login     注解对象（类级别）
     */
    @Before("@within(login) && !@annotation(com.litisle.commonsecurity.annotation.Login)")
    public void checkClassLogin(JoinPoint joinPoint, Login login) {
        // 方法级 @Anonymous 优先，跳过登录校验
        if (isAnonymous(joinPoint)) {
            return;
        }
        checkLogin(joinPoint, login.message());
    }

    /**
     * 执行登录校验
     *
     * @param joinPoint 连接点
     * @param message   校验失败时的提示消息
     */
    private void checkLogin(JoinPoint joinPoint, String message) {
        SecurityContext context = SecurityContextHolder.getContext();
        if (context == null || !context.isAuthenticated()) {
            log.warn("未登录访问受保护接口: method={}", getMethodName(joinPoint));
            throw new BizException(ErrorCode.UNAUTHORIZED, message);
        }
        log.debug("登录校验通过: userId={}, method={}", context.getUserId(), getMethodName(joinPoint));
    }

    /**
     * 判断方法是否标注 @Anonymous
     *
     * @param joinPoint 连接点
     * @return true=方法标注了 @Anonymous
     */
    private boolean isAnonymous(JoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        return method.isAnnotationPresent(Anonymous.class);
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