package com.litisle.commonredis.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commonredis.blacklist.TokenBlacklistService;
import com.litisle.commonredis.constant.CacheConstant;
import com.litisle.commonredis.lock.DistributedLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 设备会话管理器
 *
 * 【设计说明】
 * 1. 记录用户在线设备，支持多设备并发限制（超限挤下线最早设备）与踢人下线
 * 2. Redis 结构：Hash
 *    - Key   ：auth:session:{userId}（复用 CacheConstant.USER_SESSION）
 *    - field ：tokenId（JWT 的 jti）
 *    - value ：DeviceSession 序列化后的 JSON
 * 3. 懒清理机制：Redis Hash 不支持 field 级 TTL，读取时根据
 *    DeviceSession.tokenExpireTime 过滤已过期条目并 HDEL，
 *    所有 field 删除后 Redis 自动回收 Hash key，零残留
 * 4. 踢人联动黑名单：踢出设备时调用 TokenBlacklistService.addToBlacklist()，
 *    使被踢 Token 在网关处立即失效（否则 Token 在过期前仍然可用）
 *
 * 【为什么放在 common-redis 而非 common-security】
 * - 与 TokenBlacklistService 一致：本质是 Redis 数据结构操作，不含安全逻辑
 * - 遵循「基于 Redis 的功能放在 common-redis」的既有架构决策
 *
 * 【并发安全】
 * - registerSession（注册+超限踢人）存在竞态：两台设备同时登录可能瞬间超限
 * - 通过 DistributedLock（Redisson）保证同一用户的会话操作串行化
 * - 锁 Key：lock:device_session:{userId}，粒度为单用户，互不影响
 * - DistributedLock 为可选依赖（ObjectProvider）：Redisson 不可用时降级为无锁执行，
 *   与 IdempotentAspect 处理可选依赖的方式一致
 *
 * 【使用场景】
 * - 登录成功：registerSession() → 注册设备 + 超限挤下线最早设备
 * - 退出登录：removeSession() → 移除当前设备会话（黑名单由调用方处理）
 * - 设备管理页：getOnlineSessions() → 展示在线设备列表
 * - 踢指定设备：kickSession() → "下线该设备"按钮
 * - 修改密码/注销：kickAllSessions() → 全端强制下线
 *
 * @author litisle
 */
@Slf4j
@RequiredArgsConstructor
public class DeviceSessionManager {

    /**
     * 字符串 Redis 模板（Hash 读写）
     *
     * 【说明】与 TokenBlacklistService 保持一致使用 StringRedisTemplate
     * - DeviceSession 手动序列化为 JSON 字符串后存入，反序列化时再解析
     * - 相比 RedisTemplate<String, Object> 的自动序列化，JSON 结构可控且可读性强
     */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * Token 黑名单服务（踢人时联动加黑名单）
     */
    private final TokenBlacklistService tokenBlacklistService;

    /**
     * 分布式锁（可选依赖）
     *
     * 【说明】通过 ObjectProvider 延迟获取：
     * - Redisson 可用 → 注册/踢人操作加锁，保证并发安全
     * - Redisson 不可用 → 降级为无锁执行，接受微小竞态（仅影响多设备计数精度）
     */
    private final ObjectProvider<DistributedLock> distributedLockProvider;

    /**
     * JSON 序列化器（DeviceSession ↔ JSON 字符串）
     *
     * 【说明】局部构建而非注入 Spring 的 ObjectMapper：
     * - DeviceSession 字段全部为 String/Long，无特殊序列化配置需求
     * - 自包含设计，避免依赖消费方对 ObjectMapper 的定制（如日期格式）产生影响
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 分布式锁 Key 前缀
     * 完整 Key：lock:device_session:{userId}
     */
    private static final String LOCK_KEY_PREFIX = CacheConstant.LOCK_PREFIX + "device_session:";

    /**
     * 获取锁的等待时间（秒）
     * 【说明】会话操作均为毫秒级 Redis 命令，5 秒等待已十分宽裕；
     * 超时说明锁竞争异常或持有者未释放，直接失败让调用方稍后重试
     */
    private static final long LOCK_WAIT_SECONDS = 5;

    // ==================== 1. 会话注册（登录时调用） ====================

    /**
     * 注册设备会话（登录成功后调用）
     *
     * 【执行流程】
     * 1. 获取用户级分布式锁（lock:device_session:{userId}）
     * 2. 懒清理：读取并移除已过期的会话
     * 3. HSET 写入新会话（field=tokenId, value=JSON）
     * 4. 超限检查：会话数 > maxDevices 时，按 loginTime 升序逐个踢出最早设备
     *    （踢出 = HDEL + Token 加黑名单）
     * 5. 返回被挤下线的设备列表（通常为空或 1 个，调用方可用于通知）
     *
     * 【调用方示例】
     * // AuthService 密码登录成功后：
     * DeviceSession session = DeviceSession.builder()
     *         .tokenId(tokenId)
     *         .deviceType("WEB")
     *         .deviceName(parseUserAgent(request))
     *         .ip(clientIp)
     *         .loginTime(System.currentTimeMillis())
     *         .tokenExpireTime(expireTime)
     *         .build();
     * List<DeviceSession> kicked = deviceSessionManager
     *         .registerSession(userId, session, 2); // 最多 2 端在线
     *
     * @param userId     用户 ID
     * @param session    本次登录的设备会话信息
     * @param maxDevices 最大同时在线设备数（由 auth 模块配置传入，如 2）
     * @return 被挤下线的设备列表（未超限时为空列表）
     * @throws BizException Redis 操作失败或获取锁超时
     */
    public List<DeviceSession> registerSession(Long userId, DeviceSession session, int maxDevices) {
        if (userId == null || session == null || session.getTokenId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "会话注册参数不完整");
        }

        return executeWithLock(userId, () -> {
            // 1. 懒清理：先移除已过期的会话，确保计数准确
            Map<String, DeviceSession> all = loadAndCleanExpired(userId);

            // 2. 写入新会话
            hsetSession(userId, session);
            all.put(session.getTokenId(), session);

            // 3. 超限踢出：按 loginTime 升序，逐个踢出最早的设备
            List<DeviceSession> kicked = new ArrayList<>();
            while (all.size() > maxDevices) {
                DeviceSession earliest = all.values().stream()
                        .min(Comparator.comparingLong(DeviceSession::getLoginTime))
                        .orElse(null);
                if (earliest == null) {
                    break;
                }
                doKick(userId, earliest);
                all.remove(earliest.getTokenId());
                kicked.add(earliest);
                log.info("设备超限被挤下线: userId={}, tokenId={}, device={}",
                        userId, earliest.getTokenId(), earliest.getDeviceName());
            }
            return kicked;
        });
    }

    // ==================== 2. 会话移除（退出登录时调用） ====================

    /**
     * 移除设备会话（退出登录时调用）
     *
     * 【设计说明】
     * - 仅删除 Hash 条目，不加 Token 黑名单
     * - 黑名单由调用方（AuthService.logout）根据是否需要 Token 立即失效自行决定
     * - 职责单一：会话记录的移除与 Token 吊销解耦
     *
     * @param userId  用户 ID
     * @param tokenId JWT 唯一标识（jti）
     * @return true=移除成功，false=会话不存在（可能已过期被懒清理）
     */
    public boolean removeSession(Long userId, String tokenId) {
        if (userId == null || tokenId == null) {
            return false;
        }
        String key = CacheConstant.USER_SESSION + userId;
        Long deleted = stringRedisTemplate.opsForHash().delete(key, tokenId);
        boolean removed = deleted != null && deleted > 0;
        if (removed) {
            log.debug("会话已移除: userId={}, tokenId={}", userId, tokenId);
        }
        return removed;
    }

    // ==================== 3. 会话查询 ====================

    /**
     * 获取用户所有在线设备会话（懒清理后）
     *
     * 【执行流程】
     * 1. HGETALL 读取全部会话
     * 2. 过滤并 HDEL 已过期的条目（tokenExpireTime < 当前时间）
     * 3. 反序列化失败的脏数据一并清理
     * 4. 返回有效会话列表
     *
     * 【使用场景】
     * - "我的在线设备"管理页展示
     *
     * @param userId 用户 ID
     * @return 在线设备会话列表（已过滤过期，可能为空列表）
     */
    public List<DeviceSession> getOnlineSessions(Long userId) {
        Map<String, DeviceSession> sessions = loadAndCleanExpired(userId);
        return new ArrayList<>(sessions.values());
    }

    /**
     * 获取用户在线设备数
     *
     * @param userId 用户 ID
     * @return 在线设备数（已过滤过期会话）
     */
    public int getOnlineSessionCount(Long userId) {
        return getOnlineSessions(userId).size();
    }

    // ==================== 4. 踢人下线 ====================

    /**
     * 踢出指定设备（管理员踢人 / 用户主动下线某设备）
     *
     * 【执行流程】
     * 1. HGET 读取目标会话（不存在返回 false）
     * 2. HDEL 移除会话记录
     * 3. 计算剩余 TTL = tokenExpireTime - now，调用黑名单服务使 Token 立即失效
     *
     * @param userId  用户 ID
     * @param tokenId 目标设备的 JWT 唯一标识（jti）
     * @return true=踢出成功，false=会话不存在（可能已过期）
     */
    public boolean kickSession(Long userId, String tokenId) {
        if (userId == null || tokenId == null) {
            return false;
        }

        String key = CacheConstant.USER_SESSION + userId;
        Object raw = stringRedisTemplate.opsForHash().get(key, tokenId);
        if (raw == null) {
            return false;
        }

        DeviceSession session = deserialize(tokenId, raw.toString());
        stringRedisTemplate.opsForHash().delete(key, tokenId);

        if (session != null) {
            doKickBlacklist(session);
        }
        log.info("设备已踢出: userId={}, tokenId={}", userId, tokenId);
        return true;
    }

    /**
     * 踢出用户全部设备（修改密码 / 注销账号时调用）
     *
     * 【执行流程】
     * 1. 读取全部有效会话
     * 2. 逐个移除并加入 Token 黑名单
     *
     * 【使用场景】
     * - 修改密码后强制全端重新登录
     * - 管理员封禁账号时立即阻断所有访问
     *
     * @param userId 用户 ID
     * @return 被踢出的设备会话列表
     */
    public List<DeviceSession> kickAllSessions(Long userId) {
        if (userId == null) {
            return new ArrayList<>();
        }

        Map<String, DeviceSession> all = loadAndCleanExpired(userId);
        List<DeviceSession> kicked = new ArrayList<>(all.values());
        for (DeviceSession session : kicked) {
            doKick(userId, session);
        }
        log.info("全端下线完成: userId={}, count={}", userId, kicked.size());
        return kicked;
    }

    // ==================== 5. 内部工具方法 ====================

    /**
     * 读取全部会话并懒清理过期/脏数据
     *
     * 【清理规则】
     * - tokenExpireTime < 当前时间 → 会话过期，HDEL
     * - JSON 反序列化失败 → 脏数据，HDEL（不影响其他会话）
     * - 所有 field 删除后 Redis 自动回收 Hash key，无需额外处理
     *
     * @param userId 用户 ID
     * @return 有效会话 Map（key=tokenId, value=DeviceSession）
     */
    private Map<String, DeviceSession> loadAndCleanExpired(Long userId) {
        String key = CacheConstant.USER_SESSION + userId;
        Map<Object, Object> raw = stringRedisTemplate.opsForHash().entries(key);
        Map<String, DeviceSession> result = new HashMap<>();

        for (Map.Entry<Object, Object> entry : raw.entrySet()) {
            String tokenId = entry.getKey().toString();
            DeviceSession session = deserialize(tokenId, entry.getValue().toString());
            if (session == null) {
                continue;
            }
            long expireAt = session.getTokenExpireTime() == null ? 0 : session.getTokenExpireTime();
            if (expireAt < System.currentTimeMillis()) {
                stringRedisTemplate.opsForHash().delete(key, tokenId);
                log.debug("懒清理过期会话: userId={}, tokenId={}", userId, tokenId);
                continue;
            }
            result.put(tokenId, session);
        }
        return result;
    }

    /**
     * 写入单个会话到 Hash
     */
    private void hsetSession(Long userId, DeviceSession session) {
        String key = CacheConstant.USER_SESSION + userId;
        try {
            String json = OBJECT_MAPPER.writeValueAsString(session);
            stringRedisTemplate.opsForHash().put(key, session.getTokenId(), json);
        } catch (Exception e) {
            log.error("会话写入失败: userId={}, tokenId={}", userId, session.getTokenId(), e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "操作失败，请稍后重试");
        }
    }

    /**
     * 执行踢出（移除会话 + Token 加黑名单）
     */
    private void doKick(Long userId, DeviceSession session) {
        String key = CacheConstant.USER_SESSION + userId;
        stringRedisTemplate.opsForHash().delete(key, session.getTokenId());
        doKickBlacklist(session);
    }

    /**
     * 将会话对应的 Token 加入黑名单
     *
     * 【TTL 设计】剩余有效期 = tokenExpireTime - 当前时间
     * - Token 本身已过期（TTL <= 0）则无需加黑名单（解析自然失败）
     * - 由 TokenBlacklistService 内部校验并跳过
     */
    private void doKickBlacklist(DeviceSession session) {
        long expireAt = session.getTokenExpireTime() == null ? 0 : session.getTokenExpireTime();
        long ttlSeconds = (expireAt - System.currentTimeMillis()) / 1000;
        tokenBlacklistService.addToBlacklist(session.getTokenId(), ttlSeconds);
    }

    /**
     * JSON 反序列化（失败返回 null，脏数据由调用方清理）
     */
    private DeviceSession deserialize(String tokenId, String json) {
        try {
            return OBJECT_MAPPER.readValue(json, DeviceSession.class);
        } catch (Exception e) {
            log.warn("会话反序列化失败（脏数据）: tokenId={}", tokenId, e);
            return null;
        }
    }

    /**
     * 用户级分布式锁执行模板
     *
     * 【降级策略】DistributedLock 不可用时（Redisson 未引入）直接执行：
     * - 仅牺牲多设备计数的强一致（极端并发下可能短暂多 1 台设备）
     * - 不影响数据正确性（踢人与黑名单操作本身幂等）
     *
     * @param userId 用户 ID（锁粒度：单用户）
     * @param action 锁内执行的业务逻辑
     * @return action 的返回值
     */
    private <T> T executeWithLock(Long userId, Supplier<T> action) {
        DistributedLock lock = distributedLockProvider.getIfAvailable();
        if (lock == null) {
            return action.get();
        }

        String lockKey = LOCK_KEY_PREFIX + userId;
        boolean acquired = false;
        try {
            acquired = lock.tryLock(lockKey, LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("获取会话锁超时: userId={}", userId);
                throw new BizException(ErrorCode.SYSTEM_ERROR, "操作繁忙，请稍后重试");
            }
            return action.get();
        } finally {
            if (acquired) {
                lock.unlock(lockKey);
            }
        }
    }
}