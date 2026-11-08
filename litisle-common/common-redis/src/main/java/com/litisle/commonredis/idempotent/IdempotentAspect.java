package com.litisle.commonredis.idempotent;

import com.litisle.commoncore.constant.HeaderConstant;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.resolver.UserIdResolver;
import com.litisle.commonredis.constant.IdempotentConstant;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * 接口幂等性切面
 *
 * 【设计说明】
 * 1. 拦截标注 @Idempotent 的方法，在执行前校验是否重复提交
 * 2. 基于 Redis SETNX（setIfAbsent）实现分布式幂等
 * 3. 【方案 A】通过 UserIdResolver 接口获取 userId（可选注入），传给 KeyBuilder
 *
 * 【依赖倒置设计】
 * - common-redis 通过 UserIdResolver 接口获取 userId，不直接依赖认证框架
 * - 通过 common-core 的 UserIdResolver 接口解耦：
 *   - IdempotentAspect 依赖 UserIdResolver 接口（@Autowired required=false）
 *   - common-security 提供 SecurityContextUserIdResolver 实现
 *   - 业务服务同时引入两者时，Spring 自动注入实现
 *   - 业务服务只引入 common-redis 时，resolver 为 null，用 anonymous
 *
 * 【SETNX 原理】
 * - SETNX = SET if Not eXists
 * - Redis 原子操作：key 不存在则设置成功返回 true，已存在返回 false
 * - Spring Data Redis 封装为 redisTemplate.opsForValue().setIfAbsent(key, value, ttl)
 * - 原子性保证：即使多个请求同时到达，也只有一个能设置成功
 *
 * 【执行流程】
 * 1. 请求进入 → 切面拦截
 * 2. 通过 UserIdResolver 获取 userId（无实现则用 anonymous:{IP}）
 * 3. 构建 key（userKey + 接口路径 + 参数MD5 / SpEL 业务键）
 * 4. Redis SETNX，设置 TTL
 *    - true：首次请求，放行执行业务
 *    - false：重复请求，抛出异常
 * 5. 业务执行失败时，按 releaseOnError 配置决定是否释放 key
 *
 * 【面试要点】
 * - 依赖倒置原则（DIP）：高层依赖抽象，低层实现抽象
 * - @Autowired(required=false)：可选注入，保证 common-redis 可独立使用
 * - SETNX 的原子性：Spring Data Redis 的 setIfAbsent(key, value, timeout, unit) 是原子操作
 *
 * @author litisle
 */
@Slf4j
@Aspect
public class IdempotentAspect {

    /**
     * Redis 操作模板（由 common-redis 的 RedisConfig 注入）
     */
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 幂等 key 构建器
     */
    private final IdempotentKeyBuilder keyBuilder;

    /**
     * 用户ID 解析器（可选注入）
     * 【说明】
     * - 由 common-security 的 SecurityContextUserIdResolver 提供实现
     * - required=false：业务服务未引入 common-security 时为 null，用 anonymous
     * - 体现"可选依赖"设计，保证 common-redis 模块可独立工作
     */
    private UserIdResolver userIdResolver;

    /**
     * SpEL 表达式解析器（线程安全，可复用）
     */
    private static final ExpressionParser SPEL_PARSER = new SpelExpressionParser();

    public IdempotentAspect(RedisTemplate<String, Object> redisTemplate, IdempotentKeyBuilder keyBuilder) {
        this.redisTemplate = redisTemplate;
        this.keyBuilder = keyBuilder;
    }

    /**
     * 环绕通知：校验幂等性 + 业务失败释放 key
     *
     * @param joinPoint  连接点
     * @param idempotent 注解对象
     */
    @Around("@annotation(idempotent)")
    public Object checkIdempotent(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        // 1. 获取接口路径（类名:方法名，作为 key 的一部分）
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String methodPath = method.getDeclaringClass().getSimpleName() + ":" + method.getName();

        // 2. 解析用户标识（登录用户为 userId，匿名用户为 anonymous:{IP}）
        String userKey = resolveUserKey();

        // 3. 构建幂等 key：优先 SpEL 业务键，其次参数 MD5
        String key = buildKey(signature, methodPath, userKey, joinPoint.getArgs(), idempotent);

        // 4. 计算过期时间（统一转换为毫秒）
        long expireMillis = idempotent.timeUnit().toMillis(idempotent.expire());

        // 5. SETNX 原子操作：设置成功返回 true，已存在返回 false
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, IdempotentConstant.IDEMPOTENT_VALUE, expireMillis, TimeUnit.MILLISECONDS);

        // 6. 设置失败 → 重复提交
        if (Boolean.FALSE.equals(acquired)) {
            log.warn("幂等校验失败（重复提交）: key={}, method={}", key, methodPath);
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, idempotent.message());
        }

        log.debug("幂等校验通过: key={}, method={}", key, methodPath);

        // 7. 执行业务方法
        try {
            return joinPoint.proceed();
        } catch (Throwable e) {
            // 8. 业务失败时按配置释放 key，允许用户立即重试
            if (idempotent.releaseOnError()) {
                redisTemplate.delete(key);
                log.debug("业务执行失败，释放幂等 key: key={}, method={}", key, methodPath);
            }
            throw e;
        }
    }

    /**
     * 构建幂等 key
     * 【说明】优先使用 SpEL 业务键（如 #dto.orderNo、#requestId），为空时回退到参数 MD5
     */
    private String buildKey(MethodSignature signature, String methodPath, String userKey,
                            Object[] args, Idempotent idempotent) {
        String businessKey = resolveBusinessKey(idempotent.key(), signature, args);
        if (businessKey != null && !businessKey.isEmpty()) {
            return keyBuilder.buildWithBusinessKey(userKey, methodPath, businessKey);
        }
        return keyBuilder.build(userKey, methodPath, args, idempotent.includeArgs());
    }

    /**
     * 解析 SpEL 业务键
     * 【说明】
     * - 支持参数名（#dto.orderNo）与索引（#p0、#a0）
     * - 解析失败时降级为 null，回退到参数 MD5
     * SpEL：就是 Spring 提供的一种"字符串里写表达式，运行时动态求值"的能力。
     * "业务键"就是你自己指定的、有业务含义的、用来区分请求唯一性的标识。
     * 一句话总结：这个方法就是"把注解里的 #dto.orderNo 这种字符串表达式，在运行时变成真实值 ORD-2024-001"，让你能用有业务含义的字段做幂等控制，而不是无脑对所有参数算 MD5。
     */
    private String resolveBusinessKey(String expression, MethodSignature signature, Object[] args) {
        if (expression == null || expression.trim().isEmpty()) {
            return null;
        }
        try {
            StandardEvaluationContext context = new StandardEvaluationContext();
            String[] paramNames = signature.getParameterNames();
            if (paramNames != null) {
                for (int i = 0; i < paramNames.length && i < args.length; i++) {
                    context.setVariable(paramNames[i], args[i]);
                }
            }
            for (int i = 0; i < args.length; i++) {
                //为什么要注册两个名字？
                //因为 Spring 官方定义了两套参数索引写法，两套都支持，用户用哪个都行。
                context.setVariable("p" + i, args[i]);
                context.setVariable("a" + i, args[i]);
//                前缀	    含义	                来源
//                p	    Parameter（参数）	Spring 官方 SpEL 参数引用约定
//                a	    Argument（参数/实参）	Spring 官方 SpEL 参数引用约定
            }
            Object value = SPEL_PARSER.parseExpression(expression).getValue(context);
            return value != null ? String.valueOf(value) : null;
        } catch (Exception e) {
            log.warn("SpEL 业务键解析失败，回退到参数 MD5: expression={}, error={}", expression, e.getMessage());
            return null;
        }
    }

    /**
     * 解析用户标识
     * 【修复】匿名用户不再共用 "anonymous"，改为 "anonymous:{IP}"，
     *        避免不同匿名用户互相误伤（false positive）
     */
    private String resolveUserKey() {
        Long userId = resolveUserId();
        if (userId != null) {
            return String.valueOf(userId);
        }
        return IdempotentConstant.ANONYMOUS_PREFIX + resolveClientIp();
    }

    /**
     * 解析客户端真实 IP（支持多级代理）
     * 优先级：X-Forwarded-For(首个) > X-Real-IP > RemoteAddr
     * 无 web 环境（如异步任务）时返回 {@link IdempotentConstant#UNKNOWN_IP}
     */
    private String resolveClientIp() {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) {
                return IdempotentConstant.UNKNOWN_IP;
            }
            HttpServletRequest request = attributes.getRequest();

            String ip = request.getHeader(HeaderConstant.X_FORWARDED_FOR);
            if (isBlankOrUnknown(ip)) {
                ip = request.getHeader(HeaderConstant.X_REAL_IP);
            }
            if (isBlankOrUnknown(ip)) {
                ip = request.getRemoteAddr();
            }

            // 多级代理时取第一个（client, proxy1, proxy2 → client）
            if (ip != null && ip.contains(IdempotentConstant.IP_LIST_SEPARATOR)) {
                ip = ip.split(IdempotentConstant.IP_LIST_SEPARATOR)[0].trim();
            }
            return ip != null ? ip : IdempotentConstant.UNKNOWN_IP;
        } catch (Exception e) {
            log.warn("获取客户端 IP 失败: {}", e.getMessage());
            return IdempotentConstant.UNKNOWN_IP;
        }
    }

    /**
     * 判断 IP 是否为空或未知（{@link IdempotentConstant#UNKNOWN_IP}）
     */
    private boolean isBlankOrUnknown(String ip) {
        return ip == null || ip.isEmpty() || IdempotentConstant.UNKNOWN_IP.equalsIgnoreCase(ip);
    }

    /**
     * 设置 UserIdResolver（可选依赖）
     * 由 CommonRedisAutoConfiguration 通过 ObjectProvider 注入
     */
    public void setUserIdResolver(UserIdResolver userIdResolver) {
        this.userIdResolver = userIdResolver;
    }

    private Long resolveUserId() {
        if (userIdResolver == null) {
            return null;
        }
        try {
            return userIdResolver.getUserId();
        } catch (Exception e) {
            log.warn("UserIdResolver 获取用户ID失败，使用匿名标识: {}", e.getMessage());
            return null;
        }
    }
}