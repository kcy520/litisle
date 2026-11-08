package com.litisle.commonredis.lock;



import java.util.concurrent.TimeUnit;

/**
 * 分布式锁接口
 * 
 * 【设计说明】
 * 1. 面向接口编程：定义分布式锁的统一接口
 * 2. 当前使用 Redisson 实现，未来可替换为 ZooKeeper 等
 * 3. 提供 tryLock 和 lock 两种语义：
 *    - tryLock：尝试获取锁，立即返回结果（不会阻塞）
 *    - lock：获取锁，如果失败则等待（会阻塞）
 *    - unlock：释放锁
 *
 * 【Key 命名约定】
 * - key 必须由调用方给出完整名称，并自带 `lock:` 前缀（工具内部不再补充任何前缀）
 * - 正例：lock.tryLock("lock:order:create:123", 5, TimeUnit.SECONDS)
 * - 反例：lock.tryLock("order:create:123", 5, TimeUnit.SECONDS)
 *   （缺 `lock:` 会导致锁键没有统一命名空间，运维按前缀排查锁时找不到）
 * - 同一把锁的 tryLock 与 unlock 必须传完全相同的 key，否则会锁一把、解另一把
 *
 * 【使用示例】
 * // 方式1：自动释放（推荐）
 * DistributedLock lock = redissonLock;
 * try {
 *     boolean acquired = lock.tryLock("lock:order:1", 10, TimeUnit.SECONDS);
 *     if (!acquired) {
 *         return Result.error("系统繁忙，请稍后重试");
 *     }
 *     // ... 执行业务逻辑
 * } finally {
 *     lock.unlock("lock:order:1");
 * }
 * 
 * // 方式2：等待获取锁
 * lock.lock("lock:stock:deduct", 30, TimeUnit.SECONDS);
 * try {
 *     // ... 执行业务逻辑
 * } finally {
 *     lock.unlock("lock:stock:deduct");
 * }
 * 
 * @author litisle
 */
public interface DistributedLock {

    /**
     * 尝试获取锁（最多等待 waitTime，租期 -1 启用看门狗）
     * 
     * 【说明】
     * - 最多等待 waitTime 去抢锁：waitTime=0 时才是“立即返回不等待”；大于 0 时会阻塞等待
     * - 抢到锁后租期固定为 -1，由 Redisson 看门狗持续续期，只有显式 unlock 才释放
     * - 获取成功返回 true，失败（等待超时或线程被中断）返回 false
     * - 适用于：能立刻处理就处理，不能就放弃；或允许短暂排队等待的场景
     * - 需要“到期自动释放、不续期”的语义，请用 tryLock(key, waitTime, leaseTime, unit)
     * 
     * 【使用场景】
     * - 库存预扣：抢购时，如果获取不到锁就返回"手慢了"
     * - 重复提交：防止用户快速点击提交按钮
     * 
     * @param key      锁的完整 Key（必须由调用方给全名并自带 lock: 前缀，如 "lock:order:create:123"）
     * @param waitTime 最多等待获取锁的时间（不是租期，到达后不再等待并返回 false，不会导致锁自动释放）
     * @param unit     时间单位
     * @return true=在 waitTime 内抢到锁；false=等待超时或线程被中断
     */
    boolean tryLock(String key, long waitTime, TimeUnit unit);

    /**
     * 尝试获取锁（固定租期版本，最多等待 waitTime）。
     *
     * 【说明】
     * - 最多等待 waitTime 去抢锁；拿到锁后按 leaseTime 到期自动释放，**不启用看门狗续期**
     * - 租期到点即释放，即使业务还没执行完；适合“能在租期内做完、失败后下一轮可重试”的任务
     * - leaseTime 传 -1 或任何非正数、waitTime 传负数，直接抛 IllegalArgumentException，
     *   避免调用方误以为“传 -1 就是开启看门狗”
     *
     * 【与 tryLock(key, waitTime, unit) 的区别】
     * - 旧重载：租期固定 -1，Redisson 看门狗持续续期，锁只有显式 unlock 才释放
     *   （消费线程卡死时锁可能被长期续期占用）
     * - 本重载：租期固定为 leaseTime，到期自动释放，靠 TTL 自愈（不依赖看门狗）
     *
     * 【适用场景】
     * - 定时任务、对账、补偿扫描等“允许多实例轮转、超时自动放锁、下一轮幂等重试”的互斥
     * - 不希望锁被看门狗长期续期的长任务（进程异常时最多占用到 leaseTime）
     *
     * @param key       锁的完整 Key（必须由调用方给出全名，含 lock: 前缀；工具不再补前缀）
     * @param waitTime  最多等待获取锁的时间（0 表示不等待，抢不到立即返回 false）
     * @param leaseTime 持有锁的固定租期（必须为正数，到期自动释放且不续期）
     * @param unit      时间单位
     * @return true=在 waitTime 内抢到锁；false=等待超时或线程被中断
     * @throws IllegalArgumentException leaseTime 非正数，或 waitTime 为负数
     */
    boolean tryLock(String key, long waitTime, long leaseTime, TimeUnit unit);

    /**
     * 获取锁（阻塞等待）
     * 
     * 【说明】
     * - 获取不到锁时会阻塞等待
     * - 持有锁后，leaseTime 到期自动释放（防止死锁）
     * - 适用于：必须获取锁才能继续的场景
     * 
     * 【使用场景】
     * - 定时任务：确保集群中只有一个节点执行
     * - 数据同步：确保同一时间只有一个线程在同步数据
     * 
     * @param key       锁的 Key
     * @param leaseTime 持有锁的最大时间（到期自动释放）
     * @param unit      时间单位
     */
    void lock(String key, long leaseTime, TimeUnit unit);

    /**
     * 释放锁
     * 
     * 【说明】
     * 1. 释放当前线程持有的锁
     * 2. 必须在 finally 块中调用，确保锁一定会释放
     * 3. 只释放当前线程持有的锁，不会误释放其他线程的锁
     * 
     * 【重要】
     * 不释放锁会导致：
     * - 其他线程永远获取不到锁（死锁）
     * - 使用 leaseTime 自动释放虽然可以避免死锁
     *   但可能会导致业务未完成就释放锁
     *
     * 【异常行为】
     * - 非持有线程调用释放：忽略并记录 warn 日志，不抛出异常（不影响调用方 finally 流程）
     * - 其他异常（如 Redis 不可用）：记录 error 日志（含 key、线程名、异常类型），不静默吞掉
     *   此时锁可能残留到租期结束；若租期为 -1，会由看门狗继续续期直到续期失败或进程退出
     * 
     * @param key 锁的完整 Key（必须与 tryLock/lock 传入的 key 完全一致）
     */
    void unlock(String key);
}