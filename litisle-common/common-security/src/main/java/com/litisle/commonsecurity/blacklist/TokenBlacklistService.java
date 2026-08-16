//package com.litisle.commonsecurity.blacklist;
//
//import com.litisle.commoncore.exception.BizException;
//import com.litisle.commoncore.exception.ErrorCode;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.data.redis.core.StringRedisTemplate;
//
//import java.time.Duration;
//
///**
// * Token 黑名单服务
// *
// * 【设计说明】
// * 1. 用户退出登录或管理员踢人时，将 Token 的 jti（JWT ID）加入 Redis 黑名单
// * 2. 网关 JwtAuthGlobalFilter 校验 JWT 后查询黑名单，已拉黑直接返回 401
// * 3. 黑名单 TTL = JWT 剩余有效期（Token 过期后 Redis 自动清理，零运维）
// * 4. 使用 StringRedisTemplate 存储，Key 格式：auth:token:blacklist:{tokenId}，Value 为 "1"
// *
// * 【为什么用 Redis 而不是内存】
// * - 网关和业务服务可能部署多个实例，内存黑名单无法共享
// * - Redis 天然支持 TTL，无需定时任务清理过期数据
// * - 与已有基础设施（CacheConstant、RedisConfig）无缝衔接
// *
// * 【TTL 设计】
// * - 黑名单 TTL = Token 剩余有效期（exp - now）
// * - Token 本身过期后，JWT 解析就会失败，无需再查黑名单
// * - 黑名单随 Token 一同过期，Redis 内存自动回收
// *
// * 【使用场景】
// * - 用户主动退出：AuthService.logout() → addToBlacklist(tokenId, ttl)
// * - 管理员踢人：DeviceSessionManager.kickDevice() → addToBlacklist(tokenId, ttl)
// * - 网关校验：JwtAuthGlobalFilter → isBlacklisted(tokenId) → 401
// * - 刷新 Token 时旧 Token 失效：refreshToken() → addToBlacklist(oldTokenId, ttl)
// *
// * 【线程安全】
// * - StringRedisTemplate 是线程安全的
// * - 本类无状态，所有方法均可并发调用
// *
// * @author litisle
// */
//@Slf4j
//@RequiredArgsConstructor
//public class TokenBlacklistService {
//
//    /**
//     * 字符串 Redis 模板（用于简单 Key-Value 操作）
//     *
//     * 【说明】使用 StringRedisTemplate 而非 RedisTemplate<String, Object>
//     * - 黑名单只需标记"是否存在"，无需序列化复杂对象
//     * - StringRedisTemplate 性能更好，RedisInsight 中可读性更强
//     */
//    private final StringRedisTemplate stringRedisTemplate;
//
//    /**
//     * Token 黑名单 Key 前缀
//     *
//     * 完整 Key 格式：auth:token:blacklist:{tokenId}
//    * 与 { @link  com.litisle.commonredis.constant.CacheConstant#TOKEN_BLACKLIST} 保持一致
//     */
//    public static final String TOKEN_BLACKLIST_PREFIX = "auth:token:blacklist:";
//
//    /**
//     * 黑名单标记值（仅占位，无实际意义）
//     */
//    private static final String BLACKLIST_MARKER = "1";
//
//    // ==================== 1. 黑名单操作 ====================
//
//    /**
//     * 将 Token 加入黑名单
//     *
//     * 【执行流程】
//     * 1. 参数校验：tokenId 非空、TTL > 0（Token 已过期则跳过）
//     * 2. 构建 Redis Key：auth:token:blacklist:{tokenId}
//     * 3. SET key "1" EX ttlSeconds
//     * 4. Redis 异常时抛出 BizException，由调用方处理
//     *
//     * 【调用方如何使用】
//     * // 在 AuthService.logout() 中：
//     * String tokenId = jwtTokenProvider.getTokenIdFromToken(token);
//     * Date expiration = jwtTokenProvider.getExpirationFromToken(token);
//     * long ttlSeconds = (expiration.getTime() - System.currentTimeMillis()) / 1000;
//     * if (ttlSeconds > 0) {
//     *     tokenBlacklistService.addToBlacklist(tokenId, ttlSeconds);
//     * }
//     *
//     * @param tokenId    JWT 唯一标识（jti），由 JwtTokenProvider.getTokenIdFromToken() 获取
//     * @param ttlSeconds 黑名单有效期（秒），应设为 JWT 剩余有效时间
//     * @throws BizException Redis 操作失败时抛出
//     */
//    public void addToBlacklist(String tokenId, long ttlSeconds) {
//        // 参数校验
//        if (tokenId == null || tokenId.isEmpty()) {
//            log.warn("Token ID 为空，无法加入黑名单");
//            return;
//        }
//        if (ttlSeconds <= 0) {
//            log.debug("Token 已过期或 TTL 无效，跳过黑名单添加: tokenId={}, ttl={}s", tokenId, ttlSeconds);
//            return;
//        }
//
//        String key = TOKEN_BLACKLIST_PREFIX + tokenId;
//        try {
//            stringRedisTemplate.opsForValue().set(key, BLACKLIST_MARKER, Duration.ofSeconds(ttlSeconds));
//            log.info("Token 已加入黑名单: tokenId={}, ttl={}s", tokenId, ttlSeconds);
//        } catch (Exception e) {
//            log.error("Token 加入黑名单失败: tokenId={}", tokenId, e);
//            throw new BizException(ErrorCode.SYSTEM_ERROR, "操作失败，请稍后重试");
//        }
//    }
//
//    /**
//     * 检查 Token 是否在黑名单中
//     *
//     * 【执行流程】
//     * 1. 参数校验：tokenId 非空
//     * 2. Redis EXISTS auth:token:blacklist:{tokenId}
//     * 3. 存在 → Token 已被拉黑，返回 true
//     * 4. 不存在 / Redis 异常 → 返回 false（容错策略：Redis 不可用时放行，避免全站瘫痪）
//     *
//     * 【性能】
//     * - Redis EXISTS 命令为 O(1)，毫秒级响应
//     * - 网关每次请求都调用，对性能影响极小
//     * - 后续可优化为布隆过滤器前置过滤（减少 Redis 查询）
//     *
//     * 【容错策略】
//     * - Redis 异常时返回 false（放行），记录 ERROR 日志待运维排查
//     * - 这是有意的"可用性优先"设计：宁可放行一个已拉黑的 Token，也不能让全站不可用
//     * - Token 本身还有 JWT 过期时间兜底，安全风险可控
//     *
//     * @param tokenId JWT 唯一标识（jti）
//     * @return true=Token 已被拉黑（应拒绝请求），false=Token 正常或 Redis 不可用
//     */
//    public boolean isBlacklisted(String tokenId) {
//        if (tokenId == null || tokenId.isEmpty()) {
//            return false;
//        }
//
//        String key = TOKEN_BLACKLIST_PREFIX + tokenId;
//        try {
//            Boolean exists = stringRedisTemplate.hasKey(key);
//            boolean blacklisted = Boolean.TRUE.equals(exists);
//            if (blacklisted) {
//                log.debug("Token 命中黑名单: tokenId={}", tokenId);
//            }
//            return blacklisted;
//        } catch (Exception e) {
//            log.error("Token 黑名单检查失败（Redis 异常），容错放行: tokenId={}", tokenId, e);
//            // 容错：Redis 不可用时放行，避免全站瘫痪
//            return false;
//        }
//    }
//
//    // ==================== 2. 批量操作 ====================
//
//    /**
//     * 批量检查 Token 是否在黑名单中
//     *
//     * 【使用场景】
//     * - 后续支持多设备管理时，批量查询用户所有 Token 的拉黑状态
//     * - 当前阶段主要用于扩展预留
//     *
//     * @ param tokenId tokenId 集合
//     * @return true=所有 Token 都在黑名单中，false=至少有一个不在
//     */
//    public boolean areAllBlacklisted(java.util.Set<String> tokenIds) {
//        if (tokenIds == null || tokenIds.isEmpty()) {
//            return false;
//        }
//        for (String tokenId : tokenIds) {
//            if (!isBlacklisted(tokenId)) {
//                return false;
//            }
//        }
//        return true;
//    }
//}