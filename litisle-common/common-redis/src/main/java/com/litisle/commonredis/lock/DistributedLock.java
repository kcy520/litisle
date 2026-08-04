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
 * 【使用示例】
 * // 方式1：自动释放（推荐）
 * DistributedLock lock = redissonLock;
 * try {
 *     boolean acquired = lock.tryLock("order:1", 10, TimeUnit.SECONDS);
 *     if (!acquired) {
 *         return Result.error("系统繁忙，请稍后重试");
 *     }
 *     // ... 执行业务逻辑
 * } finally {
 *     lock.unlock("order:1");
 * }
 * 
 * // 方式2：等待获取锁
 * lock.lock("stock:deduct", 30, TimeUnit.SECONDS);
 * try {
 *     // ... 执行业务逻辑
 * } finally {
 *     lock.unlock("stock:deduct");
 * }
 * 
 * @author litisle
 */
public interface DistributedLock {

    /**
     * 尝试获取锁（非阻塞）
     * 
     * 【说明】
     * - 立即返回结果，不等待
     * - 获取成功返回 true，失败返回 false
     * - 适用于：能立刻处理就处理，不能就放弃的场景
     * 
     * 【使用场景】
     * - 库存预扣：抢购时，如果获取不到锁就返回"手慢了"
     * - 重复提交：防止用户快速点击提交按钮
     * 
     * @param key      锁的 Key（通常为业务标识，如 "order:create:123"）
     * @param waitTime 等待时间（到达自动释放，防止死锁）
     * @param unit     时间单位
     * @return true=获取锁成功，false=获取锁失败
     */
    boolean tryLock(String key, long waitTime, TimeUnit unit);

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
     * @param key 锁的 Key
     */
    void unlock(String key);
}