package com.litisle.commonsecurity.audit;


import com.litisle.commonsecurity.context.SecurityContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * 审计日志切面
 *
 * 【设计说明】
 * 1. 拦截标注 @AuditLog 的方法，自动记录操作日志
 * 2. 使用 @Around 环绕通知，可同时收集执行前后的信息
 * 3. 异步写入（这里用 log.info 输出，生产环境可改为 MQ + 消费入库）
 *
 * 【@Around vs @Before/@After】
 * - @Before：方法执行前，无法获取返回值和异常
 * - @After：方法执行后，无法区分成功/失败
 * - @Around：环绕，可控制是否执行、获取返回值、捕获异常、计算耗时
 * - 审计日志需要"耗时 + 结果 + 异常"，必须用 @Around
 *
 * 【执行流程】
 * 1. 用户调用 @AuditLog 方法
 * 2. 切面记录开始时间、操作人、参数
 * 3. 执行目标方法（proceed）
 * 4. 记录结束时间、耗时、返回值/异常
 * 5. 异步输出审计日志
 *
 * 【审计日志内容】
 * - 操作人：userId、username（从 SecurityContextHolder 获取）
 * - 操作内容：module、operation（注解配置）
 * - 请求参数：args（可选，敏感参数应关闭）
 * - 执行结果：成功/失败、返回值（可选）
 * - 异常信息：异常类型、堆栈（可选）
 * - 耗时：start - end
 *
 * 【面试要点】
 * - AOP 环绕通知的 proceed() 必须调用，否则目标方法不执行
 * - 异常处理：try-finally 保证日志一定记录，异常要 re-throw 不吞掉
 * - 异步化：审计日志不应阻塞主流程，应通过 MQ 或线程池异步落库
 *
 * @author litisle
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditLogAspect {

    /**
     * JSON 序列化器（用于序列化参数和返回值）
     * 【说明】通过构造器注入 Spring 容器中的 ObjectMapper
     */
    private final ObjectMapper objectMapper;

    /**
     * 环绕通知：记录审计日志
     *
     * @param joinPoint 连接点（ProceedingJoinPoint 可控制执行）
     * @param auditLog  审计日志注解
     * @return 目标方法的返回值
     * @throws Throwable 目标方法抛出的异常（原样抛出，不吞掉）
     */
    @Around("@annotation(auditLog)")
    public Object recordAuditLog(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {
        // 1. 记录开始时间
        long startTime = System.currentTimeMillis();

        // 2. 收集操作人信息
        Long userId = SecurityContextHolder.getUserId();
        String username = SecurityContextHolder.getUsername();

        // 3. 获取目标方法信息
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String className = method.getDeclaringClass().getSimpleName();
        String methodName = method.getName();

        // 4. 收集请求参数（按配置）
        String requestData = null;
        if (auditLog.saveRequestData()) {
            requestData = serializeArgs(joinPoint.getArgs());
        }

        Object result = null;
        Throwable thrown = null;
        try {
            // 5. 执行目标方法
            result = joinPoint.proceed();
            return result;
        } catch (Throwable e) {
            // 6. 捕获异常（不吞掉，记录后 re-throw）
            thrown = e;
            throw e;
        } finally {
            // 7. 计算耗时
            long costTime = System.currentTimeMillis() - startTime;

            // 8. 构建审计日志（生产环境改为异步发送 MQ）
            Map<String, Object> logMap = new HashMap<>();
            logMap.put("module", auditLog.module());
            logMap.put("operation", auditLog.operation());
            logMap.put("userId", userId);
            logMap.put("username", username);
            logMap.put("method", className + "." + methodName);
            logMap.put("costTime", costTime + "ms");
            logMap.put("status", thrown == null ? "SUCCESS" : "FAIL");

            if (auditLog.saveRequestData()) {
                logMap.put("request", requestData);
            }
            if (auditLog.saveResponseData() && result != null) {
                logMap.put("response", serializeArgs(new Object[]{result}));
            }
            if (auditLog.saveException() && thrown != null) {
                logMap.put("exception", thrown.getClass().getSimpleName() + ": " + thrown.getMessage());
            }

            // 9. 输出审计日志（生产环境：auditLogService.saveAsync(logMap) 或 MQ 发送）
            log.info("【审计日志】{}", toJson(logMap));
        }
    }

    /**
     * 序列化参数为 JSON 字符串
     * 【说明】捕获异常避免序列化失败影响主流程
     *
     * @param args 参数数组
     * @return JSON 字符串
     */
    private String serializeArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(args);
        } catch (Exception e) {
            log.warn("参数序列化失败: {}", e.getMessage());
            return "[serialize failed]";
        }
    }

    /**
     * 将 Map 转为 JSON 字符串
     */
    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return map.toString();
        }
    }
}