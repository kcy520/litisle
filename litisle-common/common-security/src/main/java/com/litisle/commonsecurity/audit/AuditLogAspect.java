package com.litisle.commonsecurity.audit;


import com.fasterxml.jackson.databind.ObjectMapper;
import cn.dev33.satoken.stp.StpUtil;
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
 * - 操作人：userId、username（从 Sa-Token StpUtil 获取）
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
@RequiredArgsConstructor
public class AuditLogAspect {

    /**
     * JSON 序列化器（用于序列化参数和返回值）
     * 【说明】通过构造器注入 Spring 容器中的 ObjectMapper
     */
    private final ObjectMapper objectMapper;

    //todo 审计的异常接收需要处理
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

        // 2. 收集操作人信息（Sa-Token 方式）
        Long userId = StpUtil.getLoginIdDefaultNull() instanceof Long
                ? (Long) StpUtil.getLoginIdDefaultNull() : null;
        String username = StpUtil.isLogin()
                ? (String) StpUtil.getSession().get("username") : null;

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
            throw e;//接住但故意不处理  切面不处理，是因为它“没资格处理”---统一由 @RestControllerAdvice 接   即全局异常处理器这个类
        } finally {
            // 7. 计算耗时
            long costTime = System.currentTimeMillis() - startTime;

            // 8. 构建审计日志（todo 生产环境改为异步发送审计 MQ）
            //log.info 看似轻量，但本质是同步磁盘 IO + 序列化
            Map<String, Object> logMap = new HashMap<>();
            logMap.put("module", auditLog.moudle());
            logMap.put("operation", auditLog.operation());
            logMap.put("userId", userId);
            logMap.put("username", username);
            logMap.put("method", className + "." + methodName);
            logMap.put("costTime", costTime + "ms");
            logMap.put("status", thrown == null ? "SUCCESS" : "FAIL");

            // ===== 审计字段按需落盘（防止日志污染 + 避免敏感泄露）=====

            // 1. 请求参数：是否记录完全由注解开关控制
            // 用途：排查问题、复现操作；密码/Token 场景必须关
            if (auditLog.saveRequestData()) {
                logMap.put("request", requestData);
            }

            // 2. 返回结果：只记录非空返回值，避免无意义字段
            // 用 Object[]{result} 统一序列化风格，防止 String/POJO 格式不一致
            // 查询接口常开，写操作按需开
            if (auditLog.saveResponseData() && result != null) {
                logMap.put("response", serializeArgs(new Object[]{result}));
            }

            // 3. 异常信息：仅失败场景记录，且只保留“类型 + 简述”
            // 不打印完整堆栈（堆栈由 error 日志负责）
            // 审计只关心“失败了 + 原因”，不做 debug 用途
            if (auditLog.saveException() && thrown != null) {
                // 例："BusinessException: 用户已存在"
                logMap.put("exception",
                        thrown.getClass().getSimpleName() + ": " + thrown.getMessage());
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