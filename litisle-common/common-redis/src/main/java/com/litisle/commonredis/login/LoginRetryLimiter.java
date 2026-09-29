package com.litisle.commonredis.login;

import com.litisle.commonredis.constant.CacheConstant;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commonredis.guard.RedisGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 登录重试限制器
 *
 * 【设计说明】
 * 1. 防止暴力破解：记录连续登录失败次数，超限后临时锁定账户
 * 2. 双 Key 结构：
 *    - login:fail:{identifier} → {count}（String，连续失败计数，自动过期）
 *    - login:lock:{identifier} → {locked}（String，锁定标记，自动过期）
 * 3. 原子操作：INCR 原子递增，首次失败时设置 TTL
 * 4. 自动过期：超过 TTL 未操作 Redis 自动回收，零运维
 *
 * 【Redis 数据结构】
 * ┌─────────────────────────────────────────────────────┐
 * │ Key：login:fail:{identifier}                        │
 * │ 类型：String  值：连续失败次数（整数）    TTL：15min  │
 * ├─────────────────────────────────────────────────────┤
 * │ Key：login:lock:{identifier}                        │
 * │ 类型：String  值："1"（仅占位标记）    TTL：15min    │
 * └─────────────────────────────────────────────────────┘
 *
 * 【清理机制】
 * - 登录成功：resetFailure() → DEL 两个 key，立即清零
 * - 自动过期：超过 TTL 未操作，Redis 自动回收，无需额外清理
 * - 失败计数 TTL 每次 INCR 时刷新（防止活跃攻击者计数永不过期）
 *
 * 【使用场景】
 * - 密码登录失败：recordFailure(username) → 递增计数，超限锁定
 * - 登录前检查：isLocked(username) → 锁定则拒绝登录，提示剩余解锁时间
 * - 登录成功：resetFailure(username) → 清除所有失败记录
 * - 前端提示：getRemainingAttempts(username) → "您还有 3 次尝试机会"
 *
 * 【为什么放在 common-redis 而非 common-security】
 * - 本质是 Redis 原子计数操作，不含安全逻辑
 * - 与 TokenBlacklistService、DeviceSessionManager 一致：基于 Redis 的功能放 common-redis
 * - litisle-auth 直接调用，无需额外依赖
 *
 * 【线程安全】
 * - StringRedisTemplate 线程安全
 * - INCR 原子操作，无竞态
 * - 锁定/解锁操作均为单条 Redis 命令，天然原子
 *
 * @author litisle
 */
@Slf4j
@RequiredArgsConstructor
public class LoginRetryLimiter {

    /**
     * 字符串 Redis 模板（简单 Key-Value 操作）
     *
     * 【说明】与 TokenBlacklistService 保持一致使用 StringRedisTemplate
     * - 只需 INCR/GET/DEL/EXISTS/EXPIRE 等基础命令
     * - 无需序列化复杂对象，StringRedisTemplate 即可满足
     */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 三态熔断守护（与 RedisRateLimiter 共用同一状态机，见 guard/RedisGuard）
     * Redis 故障降级期，本组件转本地计数兑底（见下方 localStates 与 4.x 方法）
     */
    private final RedisGuard redisGuard;

    /**
     * 本地兑底状态表（熔断降级期替代 Redis 的账户级失败计数与锁定标记）
     * key = identifier；内存有界：超过 1024 个账户时机会性清理已过期条目
     */
    private final ConcurrentHashMap<String, LocalState> localStates = new ConcurrentHashMap<>();

    /**
     * 账户锁定标记 Key 前缀
     * 完整 Key：login:lock:{identifier}
     * 【说明】引用 CacheConstant 统一管理，避免硬编码
     */
    private static final String LOGIN_LOCK_PREFIX = CacheConstant.LOGIN_LOCK;

    /**
     * 锁定标记值（仅占位，无实际意义）
     */
    private static final String LOCK_MARKER = "1";

    /**
     * 默认最大连续失败次数
     */
    private static final int DEFAULT_MAX_ATTEMPTS = 5;

    /**
     * 默认锁定持续时间（秒）
     * 3 分钟 = 180 秒
     */
    private static final long DEFAULT_LOCK_DURATION_SECONDS = 180L;

    /**
     * 失败计数默认 TTL（秒）
     * 与锁定持续时间一致：15 分钟
     * 每次 INCR 时刷新，防止活跃攻击者计数永不过期
     */
    private static final long FAIL_CNT_TTL_SECONDS = 900L;

    // ==================== 1. 核心方法 ====================

    /**
     * 记录一次登录失败
     *
     * 使用默认配置：5 次失败锁定 3 分钟
     *
     * 【执行流程】
     * 1. INCR login:fail:{identifier}（原子递增）
     * 2. 如果返回 1（首次失败），设置 TTL = 15 分钟
     * 3. 如果 count >= maxAttempts（默认 5），SET login:lock:{identifier}
     * 4. 设置锁定 key 的 TTL = 锁定持续时间（默认 3 分钟）
     *
     * 【原子性说明】
     * - INCR 本身是原子操作，但 INCR + EXPIRE 不是严格原子
     * - 极端情况下：两次并发 INCR 都返回 1，都会设置 TTL，但效果等价（幂等）
     * - 锁定检查不要求精确到毫秒，这种竞态在业务上可接受
     *
     * @param identifier 用户标识（用户名、手机号、邮箱）
     */
    public void recordFailure(String identifier) {
        recordFailure(identifier, DEFAULT_MAX_ATTEMPTS, DEFAULT_LOCK_DURATION_SECONDS);
    }

    /**
     * 记录一次登录失败（自定义阈值和锁定时间）
     *
     * @param identifier          用户标识
     * @param maxAttempts         最大连续失败次数（达到此值后锁定）
     * @param lockDurationSeconds 锁定持续时间（秒）
     */
    public void recordFailure(String identifier, int maxAttempts, long lockDurationSeconds) {
        if (identifier == null || identifier.isEmpty()) {
            log.warn("登录失败记录：identifier 为空，跳过");
            return;
        }
        if (maxAttempts <= 0) {
            maxAttempts = DEFAULT_MAX_ATTEMPTS;
        }
        if (lockDurationSeconds <= 0) {
            lockDurationSeconds = DEFAULT_LOCK_DURATION_SECONDS;
        }

        // 步骤1：熔断调度——LOCAL=降级走本地计数；PROBE=半开探测；REDIS=正常（含兑底开关关闭）
        RedisGuard.Path path = redisGuard.acquire();
        if (path == RedisGuard.Path.LOCAL) {
            localRecordFailure(identifier, maxAttempts, lockDurationSeconds);
            return;
        }

        String failKey = CacheConstant.LOGIN_FAIL_CNT + identifier;
        try {
            // 步骤2：原子递增失败计数
            Long count = stringRedisTemplate.opsForValue().increment(failKey);
            if (count == null) {
                log.error("登录失败计数 INCR 返回 null: identifier={}", identifier);
                return;
            }

            // 步骤3：首次失败设置 TTL（防止计数永不过期）
            if (count == 1) {
                stringRedisTemplate.expire(failKey, FAIL_CNT_TTL_SECONDS, TimeUnit.SECONDS);
            }

            log.debug("登录失败: identifier={}, count={}/{}, maxAttempts={}",
                    identifier, count, maxAttempts, maxAttempts);

            // 步骤4：达到阈值 → 锁定账户
            if (count >= maxAttempts) {
                String lockKey = LOGIN_LOCK_PREFIX + identifier;
                stringRedisTemplate.opsForValue().set(
                        lockKey, LOCK_MARKER, Duration.ofSeconds(lockDurationSeconds));
                log.warn("账户已锁定: identifier={}, 锁定时间={}s, 失败次数={}",
                        identifier, lockDurationSeconds, count);
            }

            // 步骤5：上报守护——清零连续故障计数；探测请求成功则恢复 CLOSED
            redisGuard.onSuccess(path == RedisGuard.Path.PROBE);
        } catch (Exception e) {
            // 步骤6：异常——上报守护计入熔断（探测失败回 OPEN / 连续达阈值则熔断打开），转本地计数兑底
            redisGuard.onFailure(path == RedisGuard.Path.PROBE, e);
            log.error("登录失败记录操作异常: identifier={}", identifier, e);
            // 容错：Redis 异常不影响登录流程（不因 Redis 故障导致用户无法登录）；
            // 但防爆破防护不能就此失效，转本地计数兑底（宁严勿松）
            localRecordFailure(identifier, maxAttempts, lockDurationSeconds);
        }
    }

    /**
     * 检查账户是否被锁定
     *
     * 【执行流程】
     * 1. EXISTS login:lock:{identifier}
     * 2. 存在 → 锁定中，返回 true
     * 3. 不存在 → 未锁定，返回 false
     *
     * 【容错策略】
     * - Redis 异常时返回 false（放行），记录 ERROR 日志
     * - 宁可放过一个暴力破解，也不能让全站用户无法登录
     *
     * @param identifier 用户标识
     * @return true=锁定中（应拒绝登录），false=未锁定或 Redis 不可用
     */
    public boolean isLocked(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return false;
        }

        // 步骤1：熔断调度——降级期转本地锁定标记判断（防爆破闸口不能因 Redis 故障失效）
        RedisGuard.Path path = redisGuard.acquire();
        if (path == RedisGuard.Path.LOCAL) {
            return localIsLocked(identifier);
        }

        String lockKey = LOGIN_LOCK_PREFIX + identifier;
        try {
            // 步骤2：EXISTS 查询锁定标记（单条命令，天然原子）
            Boolean exists = stringRedisTemplate.hasKey(lockKey);
            // 步骤3：上报守护——清零连续故障计数；探测请求成功则恢复 CLOSED
            redisGuard.onSuccess(path == RedisGuard.Path.PROBE);
            // 步骤4：判定结果（锁定中打 debug 日志便于排查）
            boolean locked = Boolean.TRUE.equals(exists);
            if (locked) {
                log.debug("账户锁定中: identifier={}", identifier);
            }
            return locked;
        } catch (Exception e) {
            // 步骤5：异常——上报守护计入熔断，转本地兑底判断（宁严勿松，不放走已锁定账户）
            redisGuard.onFailure(path == RedisGuard.Path.PROBE, e);
            log.error("账户锁定检查失败（Redis 异常），转本地兑底判断: identifier={}", identifier, e);
            return localIsLocked(identifier);
        }
    }

    /**
     * 重置登录失败记录（登录成功后调用）
     *
     * 【执行流程】
     * 1. DEL login:fail:{identifier}（删除失败计数）
     * 2. DEL login:lock:{identifier}（删除锁定标记，如有）
     * 3. 两个 key 可能不存在，DELETE 对不存在的 key 返回 0，不影响正确性
     *
     * @param identifier 用户标识
     */
    public void resetFailure(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return;
        }

        try {
            // 步骤1：DEL 两个 key（对不存在的 key 返回 0，幂等不影响正确性）
            String failKey = CacheConstant.LOGIN_FAIL_CNT + identifier;
            String lockKey = LOGIN_LOCK_PREFIX + identifier;

            stringRedisTemplate.delete(failKey);
            stringRedisTemplate.delete(lockKey);

            // 步骤2：上报守护（重置成功即 Redis 可用信号，维持熔断状态机鲜活）
            redisGuard.onSuccess(false);
            log.debug("登录失败记录已重置: identifier={}", identifier);
        } catch (Exception e) {
            // 步骤3：异常上报守护（resetFailure 非关键路径，失败仅记日志；本地状态在 finally 清除）
            redisGuard.onFailure(false, e);
            log.error("重置登录失败记录异常: identifier={}", identifier, e);
        } finally {
            // 本地兑底状态一并清除（降级期登录成功也需重置，幂等且开销极低）
            localStates.remove(identifier);
        }
    }

    // ==================== 2. 查询方法 ====================

    /**
     * 获取剩余尝试次数
     *
     * 【使用场景】
     * - 登录失败时返回给前端："您还有 3 次尝试机会"
     * - 使用默认阈值（5 次）
     *
     * @param identifier 用户标识
     * @return 剩余尝试次数（≥ 0），失败或异常时返回默认阈值
     */
    public int getRemainingAttempts(String identifier) {
        return getRemainingAttempts(identifier, DEFAULT_MAX_ATTEMPTS);
    }

    /**
     * 获取剩余尝试次数（自定义阈值）
     *
     * @param identifier  用户标识
     * @param maxAttempts 最大连续失败次数
     * @return 剩余尝试次数（≥ 0），异常时返回 maxAttempts
     */
    public int getRemainingAttempts(String identifier, int maxAttempts) {
        if (identifier == null || identifier.isEmpty()) {
            return maxAttempts;
        }

        String failKey = CacheConstant.LOGIN_FAIL_CNT + identifier;
        try {
            String value = stringRedisTemplate.opsForValue().get(failKey);
            if (value == null) {
                return maxAttempts;
            }
            int currentCount = Integer.parseInt(value);
            int remaining = maxAttempts - currentCount;
            return Math.max(remaining, 0);
        } catch (Exception e) {
            log.error("获取剩余尝试次数异常: identifier={}", identifier, e);
            return maxAttempts;
        }
    }

    /**
     * 获取锁定剩余时间（秒）
     *
     * 【使用场景】
     * - 登录时提示："账户已锁定，请 12 分钟 30 秒后重试"
     *
     * @param identifier 用户标识
     * @return 剩余锁定时间（秒），未锁定或异常时返回 0
     */
    public long getLockRemainingSeconds(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return 0;
        }

        String lockKey = LOGIN_LOCK_PREFIX + identifier;
        try {
            Long ttl = stringRedisTemplate.getExpire(lockKey, TimeUnit.SECONDS);
            if (ttl == null || ttl <= 0) {
                return 0;
            }
            return ttl;
        } catch (Exception e) {
            log.error("获取锁定剩余时间异常: identifier={}", identifier, e);
            return 0;
        }
    }

    // ==================== 3. 管理方法 ====================

    /**
     * 手动解锁账户（管理员操作）
     *
     * 【使用场景】
     * - 用户联系客服：管理员手动解锁
     * - 定时任务：批量清理异常锁定（如运维脚本）
     *
     * @param identifier 用户标识
     */
    public void unlockAccount(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return;
        }

        try {
            // 删除锁定标记
            String lockKey = LOGIN_LOCK_PREFIX + identifier;
            stringRedisTemplate.delete(lockKey);
            // 同时清除失败计数
            String failKey = CacheConstant.LOGIN_FAIL_CNT + identifier;
            stringRedisTemplate.delete(failKey);

            log.info("账户已手动解锁: identifier={}", identifier);
        } catch (Exception e) {
            log.error("手动解锁异常: identifier={}", identifier, e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "操作失败，请稍后重试");
        }
    }

    // ==================== 4. 本地兑底（Redis 熔断降级期，与 RedisRateLimiter 的降级共用 RedisGuard） ====================

    /**
     * 单账户本地兑底状态（内存版 fail 计数 + 锁定标记）
     * failExpireAtMillis 对齐 Redis 路径「每次 INCR 刷新 TTL」语义；
     * lockExpireAtMillis = 0 表示未锁定
     */
    private static final class LocalState {
        final AtomicLong count = new AtomicLong();
        volatile long failExpireAtMillis;
        volatile long lockExpireAtMillis;
    }

    /**
     * 本地兑底记录失败：计数 + TTL 刷新 + 达阈值锁定，语义与 Redis 路径一致
     * 说明：账户级计数无「集群阈值」概念，无需 instance-count 配额分摊
     */
    private void localRecordFailure(String identifier, int maxAttempts, long lockDurationSeconds) {
        long now = System.currentTimeMillis();
        // 步骤1：原子 upsert 单账户状态（compute 保证同一账户串行，无竞态）
        localStates.compute(identifier, (k, v) -> {
            // 计数已过期（超 15 分钟 TTL）则重新起算，对齐 Redis 路径 TTL 过期语义
            LocalState s = (v == null || v.failExpireAtMillis < now) ? new LocalState() : v;
            long c = s.count.incrementAndGet();
            s.failExpireAtMillis = now + FAIL_CNT_TTL_SECONDS * 1000L;
            if (c >= maxAttempts) {
                s.lockExpireAtMillis = now + lockDurationSeconds * 1000L;
            }
            return s;
        });
        // 步骤2：内存有界——超过 1024 个账户时清理已过期条目（对齐 LocalRateLimiter 的机会性清理策略）
        if (localStates.size() > 1024) {
            localStates.values().removeIf(s -> s.failExpireAtMillis < now && s.lockExpireAtMillis < now);
        }
        log.warn("登录防爆破处于本地兑底降级: identifier={}", identifier);
    }

    /**
     * 本地兑底锁定判断：锁定标记未过期即视为锁定中
     */
    private boolean localIsLocked(String identifier) {
        LocalState s = localStates.get(identifier);
        return s != null && s.lockExpireAtMillis > System.currentTimeMillis();
    }
}