package com.litisle.commonsecurity.satoken;

import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.resolver.UserIdResolver;
import lombok.extern.slf4j.Slf4j;


/**
 * Sa-Token 版 UserIdResolver 实现
 *
 * 【设计说明】
 * 1. 实现 common-core 的 UserIdResolver 接口，供 common-redis 的幂等/限流切面使用
 * 2. 通过 Sa-Token 的 StpUtil.getLoginIdDefaultNull() 获取当前登录用户ID
 * 3. 未登录时返回 null，common-redis 切面会降级为 anonymous 标识
 *
 * 【依赖倒置体现】
 * - common-core 定义接口（UserIdResolver）
 * - common-security 提供实现（SaTokenUserIdResolver）
 * - common-redis 消费接口（IdempotentAspect / RateLimiterAspect）
 * - 三者互不直接依赖，通过接口解耦
 *
 * 【为什么用 getLoginIdDefaultNull 而非 getLoginId】
 * - StpUtil.getLoginId() 在未登录时抛 NotLoginException
 * - IdempotentAspect 调用方需要 graceful 处理未登录场景
 * - getLoginIdDefaultNull() 未登录时返回 null，调用方无需 try-catch
 *
 * @author litisle
 */
@Slf4j
public class SaTokenUserIdResolver implements UserIdResolver {

    /**
     * 获取当前登录用户ID
     *
     * @return 用户ID，未登录时返回 null
     */
    @Override
    public Long getUserId() {
        try {
            Object loginId = StpUtil.getLoginIdDefaultNull();
            if (loginId == null) {
                return null;
            }
            if (loginId instanceof Long longId) {
                return longId;
            }
            // 兼容 String 类型的 loginId（如第三方登录的 openId）
            if (loginId instanceof String strId) {
                try {
                    return Long.parseLong(strId);
                } catch (NumberFormatException e) {
                    log.debug("loginId 为非数字字符串，无法转为 Long: {}", strId);
                    return null;
                }
            }
            log.warn("未知的 loginId 类型: {}", loginId.getClass().getName());
            return null;
        } catch (Exception e) {
            log.debug("Sa-Token 获取 userId 失败（可能未登录）: {}", e.getMessage());
            return null;
        }
    }
}