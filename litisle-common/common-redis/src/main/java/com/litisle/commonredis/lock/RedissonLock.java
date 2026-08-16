package com.litisle.commonredis.lock;



import com.litisle.commonredis.constant.CacheConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 基于 Redisson 的分布式锁实现
 * 
 * 【设计说明】
 * 1. 实现 DistributedLock 接口
 * 2. 使用 Redisson 的 RLock，底层基于 Redis SETNX + Lua 脚本
 * 3. Redisson 的锁是"可重入"的：同一线程可以多次获取同一把锁
 * 4. 带有"看门狗"机制：持有锁期间，Redisson 会自动续期
 * 
 * 【Redisson 锁原理】
 * 1. 获取锁：SETNX lock_key thread_id 1
 * 2. 看门狗：每 10 秒检查一次，如果锁还在，续期到 30 秒
 * 3. 释放锁：DEL lock_key（通过 Lua 脚本保证原子性）
 * 4. 锁重入：同一个线程多次 lock，锁计数 +1
 * 
 * 【使用示例】
 * @Service
 * public class OrderService {
 *     @Autowired
 *     private DistributedLock distributedLock;
 *     
 *     public void createOrder(Long productId) {
 *         String lockKey = "order:create:" + productId;
 *         try {
 *             boolean acquired = distributedLock.tryLock(lockKey, 5, TimeUnit.SECONDS);
 *             if (!acquired) {
 *                 throw new BizException("系统繁忙，请稍后重试");
 *             }
 *             // 执行业务逻辑
 *         } finally {
 *             distributedLock.unlock(lockKey);
 *         }
 *     }
 * }
 * 
 * @author litisle
 */
@Slf4j
@RequiredArgsConstructor
public class RedissonLock implements DistributedLock {

    /**
     * Redisson 客户端（通过构造器注入）
     */
    private final RedissonClient redissonClient;

    /**
     * 尝试获取锁（非阻塞）
     * 
     * @param key      锁的 Key
     * @param waitTime 等待时间
     * @param unit     时间单位
     * @return true=获取成功，false=获取失败
     */
    @Override
    public boolean tryLock(String key, long waitTime, TimeUnit unit) {
        try {
            // 构造完整的锁 Key（带上前缀，避免与其他业务冲突）
            String lockKey = CacheConstant.LOCK_PREFIX + key;
            RLock lock = redissonClient.getLock(lockKey);

            // tryLock(waitTime, leaseTime, unit)
            // - waitTime：等待获取锁的最大时间
            // - leaseTime：持有锁后自动释放的时间（-1 表示启用看门狗自动续期）
            // 注意：这里传 leaseTime=-1，启用看门狗自动续期
            boolean acquired = lock.tryLock(waitTime, -1, unit);

            if (acquired) {
                log.debug("获取锁成功: {}", lockKey);
            } else {
                log.warn("获取锁失败: {}", lockKey);
            }

            return acquired;
        } catch (InterruptedException e) {
            log.error("获取锁被中断: key={}", key, e);
            // 中断后恢复中断状态
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 获取锁（阻塞等待）
     * 
     * @param key       锁的 Key
     * @param leaseTime 持有锁的最大时间
     * @param unit      时间单位
     */
    @Override
    public void lock(String key, long leaseTime, TimeUnit unit) {
        String lockKey = CacheConstant.LOCK_PREFIX + key;
        RLock lock = redissonClient.getLock(lockKey);

        // lock(leaseTime, unit)：获取锁，如果获取不到就阻塞等待
        // - leaseTime：持有锁后自动释放的时间
        // - 传 -1 表示启用看门狗自动续期
        lock.lock(leaseTime, unit);
        log.debug("获取锁成功(阻塞): {}", lockKey);
    }

    /**
     * 释放锁
     * 
     * @param key 锁的 Key
     */
    @Override
    public void unlock(String key) {
        String lockKey = CacheConstant.LOCK_PREFIX + key;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 判断当前线程是否持有锁
            // 【说明】只有持有锁的线程才能释放锁，避免误释放
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("释放锁成功: {}", lockKey);
            }
        } catch (Exception e) {
            log.error("释放锁异常: key={}", key, e);
        }
    }
}