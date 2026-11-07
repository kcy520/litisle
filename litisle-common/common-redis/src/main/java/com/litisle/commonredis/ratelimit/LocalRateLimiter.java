package com.litisle.commonredis.ratelimit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地兜底限流器（单机固定窗口计数）
 *
 * 【为什么需要它】
 * RedisRateLimiter 统一 fail-open 后，Redis 挂掉 = 限流失效，对防爆破类接口
 * （登录/验证码）是防护缺口。本组件在 Redis 故障降级期提供「单机本地计数」，
 * 与 Redis 路径形成「常态流控 + 故障兜底」双保险终态。
 *
 * 【实现】
 * - ConcurrentHashMap<String, AtomicLong> 槽位计数，单机维度无锁读写足够快
 * - 槽位 key = 限流key#窗口结束时间戳：由墙钟窗口序号换算为绝对过期时间，
 *   清理时无需按 key 记录窗口大小（不同 key 窗口可不同）
 * - 约每 1024 次操作 purge 一次过期槽位，内存有界（上限 ≈ 活跃 key 数 × 2 个窗口槽位）
 *
 * 【集群口径说明】
 * 本组件只做「单机原始计数」，集群配额分摊由 RedisRateLimiter 在返回前
 * 乘以 instance-count 完成（等效于「本地阈值 = 集群阈值 ÷ 实例数」），
 * 保证降级期「更严或等严」，避免 N 实例各跑全量阈值把故障期变成攻击窗口。
 *
 * 【使用场景】
 * 仅由 RedisRateLimiter 在熔断 OPEN / HALF_OPEN 探测期内部调用，业务方不直接使用。
 *
 * 【与熔断器实现的关联】
 * 熔断状态机由 Resilience4j CircuitBreaker 承载（替代原自研 RedisGuard 手写三态）；
 * 本组件只负责「单机原始计数」，不感知熔断状态，也不参与状态判定。
 *
 * 【选型速查表】
 * | 场景                                   | 推荐                              |
 * |----------------------------------------|-----------------------------------|
 * | Redis 正常                             | Redis Lua 计数（集群精确）        |
 * | Redis 故障（连接/超时）                | 本组件单机计数（分摊阈值，更严）  |
 * | 需热点参数限流/控制台动态规则           | 引 Sentinel（业务模块层，非本库） |
 *
 * @author litisle
 */
public class LocalRateLimiter {

    /**
     * slots 是一张内存里的哈希表
     * 槽位表：key = 限流key#窗口结束时间戳（毫秒），value = 该窗口内累计次数
     */
    private final ConcurrentHashMap<String, AtomicLong> slots = new ConcurrentHashMap<>();

    /**
     * 总操作计数（用于约每 1024 次操作触发一次过期槽位清理）
     */
    private final AtomicLong operationCount = new AtomicLong();

    /**
     * 执行一次本地固定窗口计数并返回当前窗口内累计次数
     *
     * 【对齐墙钟窗口】与 Redis 路径的固定窗口语义一致：
     * 窗口边界取 windowSeconds 的整数倍墙钟时刻，而非「首次访问 + windowSeconds」，
     * 保证同一时刻 Redis 路径与本地路径的窗口切分点相同，切换降级时口径连续。
     *
     * 本地计数 = 用 JVM 内存模拟 Redis 的固定窗口限流：墙钟对齐算窗口边界 → 每个窗口一个 AtomicLong 计数器 → 并发自增 → 每 1024 次概率清理过期槽位 → 返回原始计数 × 实例数让调用方用集群阈值比较。
     * 设计上追求的是「Redis 故障时能兜底、恢复时无缝衔接、对正常路径零额外开销」。
     *
     * @param key           限流 key（与 Redis 路径同构，如 rate_limit:user:{id}:...）
     * @param windowSeconds 窗口大小（秒），必须为正数
     * @return 当前窗口累计次数（窗口内首次为 1）
     */
    public long increment(String key, long windowSeconds) {
        // 步骤1：计算窗口参数——秒转毫秒
        long windowMillis = windowSeconds * 1000L;

        // 步骤2：计算当前窗口的结束时刻（墙钟对齐）——
        // 当前时间戳整除窗口宽度得窗口序号，+1 后乘回宽度即为窗口结束时间戳；
        // 与 Redis 路径的固定窗口切分点一致，降级切换时口径连续
        long windowEndMillis = (System.currentTimeMillis() / windowMillis + 1) * windowMillis;

        // 步骤3：拼接槽位 key（限流key#窗口结束时间戳），同一限流维度在每个窗口各占一个槽位
        String slotKey = key + "#" + windowEndMillis;

        // 步骤4：原子自增该槽位计数（computeIfAbsent 保证并发下槽位只建一次）
            //这行代码 = 按窗口 key 找到（或创建）一个原子计数器，然后原子地 +1 并返回当前窗口的累计次数。并发安全、无锁、O(1)。
            //slots 是一张内存里的哈希表，key 是「限流维度 + 时间窗口」，value 是该窗口内的累计计数。
        long count = slots.computeIfAbsent(slotKey, k -> new AtomicLong()).incrementAndGet();

        // 步骤5：按概率触发过期槽位清理，防止长期运行内存无限增长
            //严格来说，这里用「概率」这个词，指的是单次请求触发清理的数学概率，而不是「随机掷骰子」。它描述的是一种概率性触发模型，和「确定性轮询」是同一件事的两种表述角度。
            //对任意一次 increment 调用来说：
            //→ 单次请求有 0.1% 的概率触发清理，99.9% 的概率直接跳过。这就是「按概率触发」的数学含义：每个请求独立地以固定概率决定是否执行清理。
        maybePurge();

        // 步骤6：返回当前窗口累计次数（窗口内首次为 1）
        return count;
    }

    /**
     * 约每 1024 次操作清理一次已过期槽位（窗口结束时间戳 <= 当前时刻），
     * 摊薄清理成本，避免每次请求都遍历整表
     *
     * 每一个新的时间窗口都会产生一个新的槽位 entry。如果不清理：所以必须清理过期槽位——窗口已经结束的计数没有任何业务意义，留着只是占内存。
     * maybePurge 解决的是「高频计数路径上如何零成本地防止内存泄漏」的问题：用位运算取模把 O(N) 的清理操作从「每请求执行」降级为「每 1024 次执行一次」，单次请求只多 1 个 CPU cycle 的判断开销，却保证了 slots 表的大小有界——这是典型的用空间换时间、再用概率摊薄把时间成本压回零的工程手法。
     */
    private void maybePurge() {
        // 步骤1：按位与 1023 取模（2 的幂专用位运算，比 % 快）——
        // 仅当计数低 10 位全 0（即每 1024 次操作中的 1 次）才真正清理，摊薄遍历成本
        if ((operationCount.incrementAndGet() & 0x3FFL) != 0L) {
            return;
        }

        // 步骤2：取当前时刻，作为槽位过期判断基准
        long now = System.currentTimeMillis();

        // 步骤3：遍历槽位表，移除「窗口结束时间戳 <= 当前时刻」的过期槽位；
        // 过期窗口的计数不再参与任何判断，删除不会影响进行中的窗口统计
        slots.entrySet().removeIf(entry -> {
            // 步骤3.1：解析槽位 key 尾部的时间戳（lastIndexOf 防限流 key 本身含 '#'）
            int idx = entry.getKey().lastIndexOf('#');
            if (idx < 0) {
                // 异常结构（无时间戳后缀）→ 视为脏数据直接清除
                return true;
            }
            try {
                // 步骤3.2：窗口已结束（结束时刻 <= 现在）→ 过期，移除
                return Long.parseLong(entry.getKey().substring(idx + 1)) <= now;
            } catch (NumberFormatException ex) {
                // 时间戳非法 → 同样视为脏数据清除
                return true;
            }
        });
    }
}