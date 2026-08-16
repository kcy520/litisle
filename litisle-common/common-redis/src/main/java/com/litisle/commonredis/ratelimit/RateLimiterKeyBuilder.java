package com.litisle.commonredis.ratelimit;

import com.litisle.commoncore.resolver.UserIdResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

/**
 * 限流 Key 构建器
 *
 * 【设计说明】
 * 1. 根据限流类型构建唯一 key，格式：rate_limit:{type}:{id}:{method}
 * 2. USER 类型：通过 UserIdResolver 获取 userId（依赖倒置，与 IdempotentKeyBuilder 一致）
 * 3. IP 类型：通过 RequestContextHolder 获取客户端 IP（需 web 环境）
 * 4. DEFAULT 类型：用 "global" 作为 id，全局统一限流
 *
 * 【Key 设计原则】
 * - 唯一性：不同用户/IP/接口 → 不同 key
 * - 可读性：key 包含业务含义，便于 RedisInsight 排查
 * - 长度可控：方法路径用"类名:方法名"，不用完整包名
 *
 * 【IP 获取的多级代理处理】
 * - X-Forwarded-For：标准代理头，格式 "client, proxy1, proxy2"，取第一个
 * - X-Real-IP：Nginx 反向代理设置的真实客户端 IP
 * - request.getRemoteAddr()：无代理时的直连 IP
 * - 优先级：X-Forwarded-For > X-Real-IP > RemoteAddr
 *
 * /**
 *  * 获取客户端真实 IP（支持多级代理）
 *  * 优先级：X-Forwarded-For(首个) > X-Real-IP > RemoteAddr
 *  * 注：IP 可信性由 Nginx 层保证，本方法只负责解析
 *
 * @author litisle
 */
@Slf4j
public class RateLimiterKeyBuilder {

    /**
     * Key 前缀（统一管理，避免冲突）
     */
    private static final String LIMIT_KEY_PREFIX = "rate_limit:";

    /**
     * 构建限流 key
     *
     * @param keyType  限流类型
     * @param userId   当前用户ID（由切面通过 UserIdResolver 获取后传入，未登录为 null）
     * @param resolver UserIdResolver 实例（可能为 null，业务服务未引入 common-security 时）
     * @param method   目标方法
     * @return Redis key，格式：rate_limit:{type}:{id}:{method}
     */
    public String build(RateLimiter.KeyType keyType, Long userId, UserIdResolver resolver, Method method) {
        String methodPath = method.getDeclaringClass().getSimpleName() + ":" + method.getName();
        String id;

        switch (keyType) {
            case USER:
                id = resolveUserId(userId, resolver);
                break;
            case IP:
                id = resolveClientIp();
                break;
            case DEFAULT:
            default:
                id = "global";
                break;
        }

        return LIMIT_KEY_PREFIX + keyType.name().toLowerCase() + ":" + id + ":" + methodPath;
    }

    /**
     * 解析用户ID
     * 【说明】优先用切面传入的 userId，其次用 UserIdResolver，最后降级为 anonymous
     *
     * @param userId   切面获取的 userId
     * @param resolver UserIdResolver 实例
     * @return 用户标识字符串
     */
    private String resolveUserId(Long userId, UserIdResolver resolver) {
        if (userId != null) {
            return String.valueOf(userId);
        }
        if (resolver != null) {
            return resolver.getUserKey();
        }
        return "anonymous";
    }

    /**
     * 解析客户端 IP
     * 【说明】通过 RequestContextHolder 获取当前请求，支持多级代理
     *       无 web 环境（如异步任务）时返回 "unknown"
     *
     * @return 客户端 IP
     */
    private String resolveClientIp() {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) {
                return "unknown";
            }
            HttpServletRequest request = attributes.getRequest();

            // 优先级：X-Forwarded-For > X-Real-IP > RemoteAddr
            String ip = request.getHeader("X-Forwarded-For");
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = request.getHeader("X-Real-IP");
            }
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = request.getRemoteAddr();
            }

            // 多级代理时取第一个（client, proxy1, proxy2 → client）
            if (ip != null && ip.contains(",")) {
                ip = ip.split(",")[0].trim();
            }
            return ip != null ? ip : "unknown";
        } catch (Exception e) {
            log.warn("获取客户端 IP 失败: {}", e.getMessage());
            return "unknown";
        }
    }
}