package com.litisle.commonredis.lock;



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
 * 4. 带有"看门狗"机制：持有锁期间，Redisson 会自动续期（仅租期为 -1 时启用；
 *    固定租期的 tryLock 重载不启用看门狗，靠 TTL 到期自动释放）
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
 *         // key 必须由调用方给全名并自带 lock: 前缀（工具不再补前缀）
 *         String lockKey = "lock:order:create:" + productId;
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
     * 尝试获取锁（最多等待 waitTime，租期 -1 启用看门狗）
     *
     * 作用：在 waitTime 内反复尝试抢锁，抢到后租期固定为 -1（Redisson 看门狗自动续期），
     * 只有显式 unlock 才会释放；waitTime=0 时不等待，抢不到立即返回 false。
     * 若需要“到期自动放锁、不续期”，请使用四参数重载。
     *
     * @param key      锁的完整 Key（必须由调用方给全名，含 lock: 前缀；工具不再补前缀）
     * @param waitTime 最多等待获取锁的时间（不是租期，不会导致锁自动释放）
     * @param unit     时间单位
     * @return true=在 waitTime 内抢到锁；false=等待超时或线程被中断
     */
    @Override
    public boolean tryLock(String key, long waitTime, TimeUnit unit) {
        try {
            // 直接使用调用方传入的完整 key：lock: 前缀由调用方负责，工具不再补前缀
            String lockKey = key;
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
     * 尝试获取锁（固定租期版本，最多等待 waitTime）。
     *
     * 作用：与三参数 tryLock 不同，这里由调用方指定固定租期，拿到锁后到点自动释放，
     * 不启用 Redisson 看门狗续期，适合定时任务/对账这类允许超时放锁、下一轮幂等重试的场景。
     *
     * @param key       锁的完整 Key（调用方给全名，含 lock: 前缀；工具不再补前缀）
     * @param waitTime  最多等待获取锁的时间（0 表示不等待）
     * @param leaseTime 持有锁的固定租期（必须为正数，到期自动释放且不续期）
     * @param unit      时间单位
     * @return true=在 waitTime 内抢到锁；false=等待超时或线程被中断
     * @throws IllegalArgumentException leaseTime 非正数，或 waitTime 为负数
     */
    @Override
    public boolean tryLock(String key, long waitTime, long leaseTime, TimeUnit unit) {
        // 参数校验：租期必须为正，避免调用方把 -1 误当成“开启看门狗”
        if (leaseTime <= 0) {
            throw new IllegalArgumentException("leaseTime 必须为正数（本重载不启用看门狗）：leaseTime=" + leaseTime);
        }
        // 参数校验：等待时间不能为负，负数语义不明确，直接拒绝
        if (waitTime < 0) {
            throw new IllegalArgumentException("waitTime 不能为负数：waitTime=" + waitTime);
        }
        try {
            // 直接使用调用方传入的完整 key：lock: 前缀由调用方负责，工具不再补前缀
            RLock lock = redissonClient.getLock(key);

            // tryLock(waitTime, leaseTime, unit)：waitTime 内抢锁，leaseTime 到期自动释放
            // 注意：leaseTime 为正数时 Redisson 不启动看门狗，锁靠 TTL 自愈
            boolean acquired = lock.tryLock(waitTime, leaseTime, unit);

            // 记录抢锁结果，便于排查任务为何本轮未执行
            if (acquired) {
                log.debug("获取锁成功(固定租期): {}", key);
            } else {
                log.warn("获取锁失败(固定租期): {}", key);
            }

            return acquired;
        } catch (InterruptedException e) {
            // 线程被中断：记日志、恢复中断标记，并按“未抢到锁”返回（调用方本轮跳过）
            log.error("获取锁被中断: key={}", key, e);
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
        // 直接使用调用方传入的完整 key：lock: 前缀由调用方负责，工具不再补前缀
        String lockKey = key;
        RLock lock = redissonClient.getLock(lockKey);

        // lock(leaseTime, unit)：获取锁，如果获取不到就阻塞等待
        // - leaseTime：持有锁后自动释放的时间
        // - 传 -1 表示启用看门狗自动续期
        lock.lock(leaseTime, unit);
        log.debug("获取锁成功(阻塞): {}", lockKey);
    }

    /**
     * 释放锁（线程绑定；释放失败只记日志，不抛出，避免破坏调用方 finally 流程）
     *
     * 作用：仅当当前线程持有锁时才释放。非持有线程（重复释放、跨线程释放）忽略并留痕；
     * Redis 不可用等异常记录 key/线程/异常类型，便于排查残留锁。
     *
     * @param key 锁的完整 Key（必须与 tryLock/lock 传入的 key 完全一致）
     */
    @Override
    public void unlock(String key) {
        // 直接使用调用方传入的完整 key：lock: 前缀由调用方负责，工具不再补前缀
        String lockKey = key;
        RLock lock = redissonClient.getLock(lockKey);

        // 当前线程名，仅用于日志定位（Redisson 锁按线程持有，跨线程释放会被拒绝）
        String threadName = Thread.currentThread().getName();
        try {
            // 判断当前线程是否持有锁
            // 【说明】只有持有锁的线程才能释放锁，避免误释放
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("释放锁成功: {}", lockKey);
            } else {
                // 当前线程未持有锁：常见于重复 unlock 或跨线程释放，忽略但必须留痕
                log.warn("释放锁跳过（当前线程未持有锁）: key={}, thread={}", lockKey, threadName);
            }
        } catch (IllegalMonitorStateException e) {
            // 非持有线程释放：Redisson 明确拒绝，属于可忽略情况，但要留下 warn 记录
            log.warn("释放锁忽略（锁不属于当前线程）: key={}, thread={}, type={}",
                    lockKey, threadName, e.getClass().getName());
        } catch (Exception e) {
            // 其他异常（如 Redis 不可用）：不能静默吞掉，记录 key/线程/异常类型便于排查残留锁
            log.error("释放锁异常（锁可能残留至租期结束或续期停止）: key={}, thread={}, type={}",
                    lockKey, threadName, e.getClass().getName(), e);
        }
    }
}