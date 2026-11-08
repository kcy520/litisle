package com.litisle.litislereview.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Redis 互斥锁原子释放工具（review sweeper 专用）
 *
 * <p>【为什么用 Lua】原来的释放是「GET 取当前值 → 比对 token → DEL」两步。GET 与 DEL 之间有一个时间窗口：
 * 如果锁恰在这段时间 TTL 到期，另一实例可以抢到同一个键并写入自己的 token，本实例随后执行的 DEL 就会删掉他人的锁，
 * 导致两个实例同时执行同一轮清扫。Lua 脚本在 Redis 内部原子执行，「取值 + 比对 + 删除」不会被打断，
 * 只有键当前值仍等于本实例 token 时才执行删除。</p>
 *
 * <p>【为什么不复用 common-redis 的 RedissonLock】sweeper 的语义是「抢不到就跳过本轮 + 靠 TTL 自愈」，
 * 不需要等待，也不需要看门狗续期；RedissonLock 的固定租期重载还会改变键名（要求 lock: 前缀）和键的存储结构，
 * 影响现有运维巡检口径。因此这里只在 review 模块内用一段 Lua 脚本修正释放的原子性，
 * 锁的获取方式、键名与 TTL 都保持不变。</p>
 *
 * @author litisle
 */
@Slf4j
@Component
public class RedisLockReleaseSupport {

    /**
     * 原子释放脚本：仅当键当前值等于传入 token 时才删除，并返回删除条数（0 或 1）
     * - 键不存在：get 返回 false，走 else 分支返回 0
     * - 值不等于 token：说明锁已被其他实例重新持有，不删除并返回 0
     */
    private static final DefaultRedisScript<Long> RELEASE_IF_OWNED_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    /** Redis 字符串模板（与 sweeper 抢锁时使用的同一个模板） */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 构造器：注入 Redis 字符串模板
     *
     * @param stringRedisTemplate Redis 字符串模板
     */
    public RedisLockReleaseSupport(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 原子释放互斥锁：仅当锁的当前值等于本实例 token 时删除
     *
     * <p>失败策略与原实现保持一致：脚本或连接异常只记录日志、不抛出，锁由 TTL 过期自愈，
     * 避免影响 sweeper 主流程。</p>
     *
     * @param key   锁键
     * @param token 本实例抢锁时写入的唯一 token（UUID）
     * @param scene 日志场景名（如「隔离清扫」），用于保留各 sweeper 原有的日志辨识信息
     * @return true=本次确实删除；false=token 为空、值不匹配、键不存在或释放异常
     */
    public boolean releaseIfOwned(String key, String token, String scene) {
        // token 为空时直接返回：没有 token 无法证明锁归属本实例，不能执行删除
        if (!StringUtils.hasText(token)) {
            return false;
        }
        try {
            // 交给 Redis 原子执行：取值、比对、删除在同一段脚本内完成，消除两步之间的误删窗口
            Long deleted = stringRedisTemplate.execute(RELEASE_IF_OWNED_SCRIPT, List.of(key), token);
            if (deleted == null || deleted <= 0) {
                // 未删除：锁已不属于本实例（TTL 到期后被他人抢占）或键已不存在，保留低级别日志便于排查
                log.debug("{}互斥锁未删除（token 不匹配或键不存在），key={}", scene, key);
                return false;
            }
            return true;
        } catch (Exception e) {
            // 释放异常仅 warn 留痕，由锁 TTL 过期自愈；不抛出，保持原实现不影响主流程的策略
            log.warn("{}互斥锁 Lua 原子释放失败（TTL 自愈），key={}", scene, key, e);
            return false;
        }
    }
}
