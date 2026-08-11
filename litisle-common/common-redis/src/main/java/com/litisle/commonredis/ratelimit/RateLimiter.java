package com.litisle.commonredis.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 接口限流注解
 *
 * 【设计说明】
 * 1. 标注在需要限流的接口上，防止恶意刷量、保护后端服务
 * 2. 搭配 RateLimiterAspect 切面，基于 Redis + Lua 脚本实现分布式限流
 * 3. 适用于高频接口：登录、短信发送、点赞、评论、搜索等
 *
 * 【与 @Idempotent 的区别】
 * - @Idempotent：防止"重复提交"（同一请求 TTL 内只能执行一次）
 * - @RateLimiter：防止"频繁访问"（TTL 内允许 N 次，超过则拒绝）
 * - 两者可叠加使用：先限流再幂等（如点赞接口：1秒限流5次 + 1秒幂等防重）
 *
 * 【为什么放在 common-redis 而不是 common-security】
 * - 限流的本质是 Redis 计数，与 Redis 强耦合
 * - 与 @Idempotent 同级，都是"基于 Redis 的功能"
 * - 避免让 common-security 强制依赖 Redis（保持模块隔离）
 *
 * 【实现原理（固定窗口计数法）】
 * 1. 请求进入 → 构建 key（rate_limit:{type}:{id}:{method}）
 * 2. Redis 执行 Lua 脚本（原子操作）：
 *    - INCR key（计数 +1）
 *    - 如果 count == 1，设置 EXPIRE（首次访问才设置过期时间）
 * 3. 返回当前计数
 *    - count <= maxCount：放行
 *    - count > maxCount：拒绝，抛出 BizException(TOO_MANY_REQUESTS)
 *
 * 【为什么用 Lua 脚本】
 * - INCR 和 EXPIRE 必须原子执行，否则并发下会出现"计数已增但未设过期"导致永久计数
 * - Lua 脚本在 Redis 中单线程执行，保证原子性
 *
 * 【固定窗口的局限性】
 * - 窗口边界处可能出现 2 倍流量（如 1秒限流100，0.9s 来 100 + 1.1s 来 100 = 0.2s 内 200）
 * - 更精确的方案：滑动窗口（Sorted Set + 时间戳），但实现复杂、内存占用高
 * - 实习项目场景下，固定窗口已足够，且实现简洁
 *
 * 【使用示例】
 * // 示例1：登录接口，同一用户 1 分钟最多 5 次
 * @RateLimiter(time = 1, timeUnit = TimeUnit.MINUTES, count = 5, keyType = RateLimiter.KeyType.USER)
 * @PostMapping("/login")
 * public Result<LoginVO> login(@RequestBody LoginDTO dto) { ... }
 *
 * // 示例2：短信发送，同一 IP 1 小时最多 10 次
 * @RateLimiter(time = 1, timeUnit = TimeUnit.HOURS, count = 10, keyType = RateLimiter.KeyType.IP)
 * @PostMapping("/sms/send")
 * public Result<Void> sendSms(@RequestParam String phone) { ... }
 *
 * // 示例3：全局接口保护，1 秒最多 100 次
 * @RateLimiter(time = 1, count = 100, keyType = RateLimiter.KeyType.DEFAULT)
 * @GetMapping("/hot/articles")
 * public Result<List<ArticleVO>> hotArticles() { ... }
 *
 * @author litisle
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimiter {

    /**
     * 时间窗口大小（默认 1）
     */
    long time() default 1;

    /**
     * 时间单位（默认秒）
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;

    /**
     * 时间窗口内最大请求次数（默认 1）
     */
    long count() default 1;

    /**
     * 限流 key 类型（默认 USER）
     */
    KeyType keyType() default KeyType.USER;

    /**
     * 限流提示消息
     */
    String message() default "请求过于频繁，请稍后重试";

    /**
     * 限流 key 类型枚举
     */
    enum KeyType {
        /**
         * 按用户ID限流（需登录，通过 UserIdResolver 获取）
         * 适用场景：登录后的写操作（点赞、评论、发布）
         */
        USER,

        /**
         * 按客户端 IP 限流（适用于匿名接口）
         * 适用场景：登录、注册、短信发送（未登录状态）
         */
        IP,

        /**
         * 全局限流（不区分用户/IP，按方法路径限流）
         * 适用场景：保护热点查询接口（如首页推荐、热门文章）
         */
        DEFAULT
    }
}