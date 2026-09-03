package com.litisle.commonredis.idempotent;



import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 接口幂等性注解
 *
 * 【设计说明】
 * 1. 标注在需要幂等控制的接口上，防止用户重复提交
 * 2. 搭配 IdempotentAspect 切面，基于 Redis SETNX 实现分布式幂等
 * 3. 适用于写操作：下单、支付、点赞、评论提交等
 *
 * 仅仅是在ttl的时间之内防止用户重复提交
 *
 * 【为什么放在 common-redis 而不是 common-security】
 * - 幂等性的本质是 Redis SETNX，与 Redis 强耦合
 * - 与 common-redis 的分布式锁、布隆过滤器同级，都是"基于 Redis 的功能"
 * - 避免让 common-security 强制依赖 Redis（保持模块隔离）
 *
 * 【幂等性概念】
 * - 幂等：多次执行相同操作，结果与执行一次相同
 * - 天然幂等：GET 查询、DELETE 删除（按 id）
 * - 需要幂等控制：POST 创建、PUT 更新（网络重试、用户重复点击）
 *
 * 【实现原理】
 * 1. 请求进入 → 构建 key（userId + 接口路径 + 参数摘要 MD5）
 * 2. Redis SETNX（SET IF NOT EXISTS）+ TTL
 *    - 设置成功：首次请求，放行
 *    - 设置失败：重复请求，拒绝
 * 3. 业务执行完成（成功/失败），key 自然过期（不主动删除，防止并发重试）
 *
 * 【使用示例】
 * // 示例1：创建订单（5秒内不允许重复提交）
 * @Idempotent(expire = 5, timeUnit = TimeUnit.SECONDS, message = "请勿重复提交订单")
 * @PostMapping("/order/create")
 * public Result<Void> createOrder(@RequestBody OrderDTO dto) { ... }
 *
 * // 示例2：点赞（1秒内不允许重复）
 * @Idempotent(expire = 1, message = "操作太频繁")
 * @PostMapping("/article/{id}/like")
 * public Result<Void> like(@PathVariable Long id) { ... }
 *
 * @author litisle
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /**
     * 幂等过期时间（默认 5 秒）
     * 【说明】TTL 应略大于业务执行时间，但不要太长（影响用户体验）
     */
    long expire() default 5;

    /**
     * 时间单位（默认秒）
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;

    /**
     * 重复提交提示消息
     */
    String message() default "请勿重复提交";

    /**
     * 是否将参数纳入 key 计算
     * 【说明】
     * - true（默认）：相同参数视为重复，不同参数视为不同请求
     * - false：同一用户对同一接口，在过期时间内只能调用一次（不区分参数）
     * - 场景：每日签到无需参数纳入 key，下单需要参数纳入 key
     */
    boolean includeArgs() default true;

    /**
     * 业务幂等键（SpEL 表达式，可选）
     * 【说明】
     * - 优先级高于 includeArgs 的参数 MD5 摘要
     * - 支持引用方法参数名或索引：#dto.orderNo、#requestId、#p0、#a0
     * - 场景：客户端携带 requestId 重试、按订单号/流水号幂等
     * - 为空时回退到参数 MD5 摘要
     */
    String key() default "";

    /**
     * 业务执行失败时是否释放幂等 key
     * 【说明】
     * - true（默认）：方法抛异常时删除 key，允许用户立即重试
     * - false：无论成功失败，key 都保留到 TTL 过期（严格防重）
     */
    boolean releaseOnError() default true;
}