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
 * // ==================== 按使用场景补充示例 ====================
 *
 * // 示例3：每日签到 —— 无参幂等（includeArgs = false）
 * 【使用场景】同一用户一天内只需提交一次，接口本身无业务参数（或参数不影响幂等判断）
 * 【配置要点】includeArgs = false：同一用户对同一接口在 TTL 内只允许调用一次，不区分参数
 * @Idempotent(expire = 10, timeUnit = TimeUnit.SECONDS, includeArgs = false, message = "今日已签到，请勿重复提交")
 * @PostMapping("/user/checkin")
 * public Result<Void> checkin() { ... }
 *
 * // 示例4：支付扣款 —— 严格防重（releaseOnError = false）
 * 【使用场景】资金操作，即使业务失败也不允许立即重试，防止“扣款中”状态被并发重复触发
 * 【配置要点】releaseOnError = false：无论成功失败，key 都保留到 TTL 过期，重试需等 TTL 结束
 * @Idempotent(expire = 30, timeUnit = TimeUnit.SECONDS, releaseOnError = false, message = "支付处理中，请勿重复提交")
 * @PostMapping("/order/{orderNo}/pay")
 * public Result<Void> pay(@PathVariable String orderNo) { ... }
 *
 * // 示例5：客户端携带 requestId 重试 —— SpEL 业务幂等键（key）
 * idempotent:{userId}:{接口名}:{前端传来的requestId}
 * SpEL #requestId 从方法参数里取，方法参数从请求头 @RequestHeader("X-Request-Id") 来。链路通的，没问题 👍
 * 【使用场景】前端超时重试、弱网环境重发请求：客户端生成唯一 requestId，服务端按 requestId 幂等
 * 【配置要点】key 优先级高于 includeArgs 的参数 MD5，同一 requestId 重复提交直接拒绝     requestid一般为uuID格式
 * @Idempotent(expire = 60, timeUnit = TimeUnit.SECONDS, key = "#requestId", message = "请求处理中，请勿重复提交")
 * @PostMapping("/article/comment")
 * public Result<Void> comment(@RequestBody CommentDTO dto, @RequestHeader("X-Request-Id") String requestId) { ... }
 *
 * // 示例6：按业务单号幂等 —— SpEL 引用嵌套属性
 * 【使用场景】同一订单号只允许创建一次售后单，与“谁提交的/何时提交的”无关
 * 【配置要点】支持 #p0、#dto.orderNo 等写法引用方法参数及属性，天然跨用户幂等
 * @Idempotent(expire = 300, timeUnit = TimeUnit.SECONDS, key = "#dto.orderNo", message = "该订单已提交售后申请")
 * @PostMapping("/after-sale/apply")
 * public Result<Void> applyAfterSale(@RequestBody AfterSaleDTO dto) { ... }
 *
 * // ==================== 场景选型速查 ====================
 * | 场景                     | expire      | includeArgs | key   | releaseOnError |
 * |--------------------------|-------------|-------------|-------|----------------|
 * | 表单防重复点击（下单/评论）| 3~10 秒     | true        | 空    | true           |
 * | 高频操作限流式防重（点赞）| 1 秒        | true        | 空    | true           |
 * | 每日一次类操作（签到）    | 10 秒左右    | false       | 空    | true           |
 * | 资金操作（支付/提现）    | ≥ 业务耗时   | true        | 可选  | false          |
 * | 客户端重试幂等           | 覆盖重试窗口 | 无关        | 必填  | true           |
 *
 * @author litisle
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {


// 幂等 ： IdempotentAspect.java#L118-L125 —`setIfAbsent` 返回`false` 即视为重复提交直接拒绝；且 L130-L139 业务失败时按`releaseOnError` 删 key 允许重试——它关心的是“这一次操作有没有执行过”，不关心次数。

// 限流 ： RedisRateLimiter.java#L84-L90 — Lua 脚本`incr` 自增计数，`count == 1` 时才设过期； RateLimiterAspect.java#L114 —`currentCount > count` 才拒绝，窗口内前 N 次都正常放行。

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