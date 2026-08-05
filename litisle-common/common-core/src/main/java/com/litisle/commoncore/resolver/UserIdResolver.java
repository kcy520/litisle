package com.litisle.commoncore.resolver;



/**
 * 用户ID 解析器接口（依赖倒置，解耦 common-redis 与 common-security）
 *
 * 【设计说明】
 * 1. 定义在 common-core，所有模块都依赖 common-core，可共享此接口
 * 2. common-redis 的幂等切面通过此接口获取当前 userId（可选注入）
 * 3. common-security 提供默认实现 SecurityContextUserIdResolver
 * 4. 业务服务也可自定义实现（如从 Request Header 直接读取）
 *
 * 【为什么需要这个接口】
 * - common-redis 的 IdempotentAspect 需要知道"当前用户是谁"来构建幂等 key
 * - 但 common-redis 不能依赖 common-security（否则违背模块隔离原则）
 * - 通过接口定义在 common-core，实现依赖倒置（DIP）：
 *   - 高层模块（common-redis）依赖抽象（接口）
 *   - 低层模块（common-security）实现抽象
 *   - 两者都依赖 common-core 的抽象，互不依赖
 *
 * 【使用方式】
 * - common-security 实现：从 SecurityContextHolder.getUserId() 获取
 * - 业务服务自定义实现：从 Request Header 的 X-User-Id 读取
 * - 不提供实现时：IdempotentAspect 用 "anonymous" 作为用户标识
 *
 * 【面试要点】
 * - 这是"依赖倒置原则（DIP）"的典型应用
 * - 也是"开闭原则"的体现：新增用户来源只需新增实现，不改 common-redis
 * - Spring 的 @Autowired(required=false) 实现可选注入，保证 common-redis 独立可用
 *
 * @author litisle
 */
public interface UserIdResolver {

    /**
     * 获取当前用户ID
     *
     * @return 用户ID，未登录或无上下文时返回 null
     */
    Long getUserId();

    /**
     * 获取当前用户标识（用于日志、key 构建等）
     * 默认实现：userId 为 null 时返回 "anonymous"
     *
     * @return 用户标识字符串
     */
    default String getUserKey() {
        Long userId = getUserId();
        return userId != null ? String.valueOf(userId) : "anonymous";
    }
}