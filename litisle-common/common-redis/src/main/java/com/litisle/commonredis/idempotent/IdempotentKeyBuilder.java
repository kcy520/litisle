package com.litisle.commonredis.idempotent;


import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

/**
 * 幂等 Key 构建器
 *
 * 【设计说明】
 * 1. 构建唯一的幂等 key，格式：idempotent:{userId}:{接口路径}:{参数MD5}
 * 2. 使用 MD5 摘要参数，避免 key 过长（Redis key 建议 < 1KB）
 * 3. 【方案 A】接收 userId 参数，由切面通过 UserIdResolver 接口传入，KeyBuilder 不自己获取用户信息
 *
 * 【方案 A 说明】
 * - 之前 KeyBuilder 直接调用认证框架获取 userId，迁移到 common-redis 后改为接收参数
 * - 切面 IdempotentAspect 通过 UserIdResolver 接口获取 userId，再传给本类
 * - 符合"单一职责"：KeyBuilder 只负责"构建 key"，不负责"获取用户"
 *
 * 【Key 设计原则】
 * - 唯一性：不同用户、不同接口、不同参数 → 不同 key
 * - 可读性：key 包含业务含义，便于排查（如 idempotent:1001:/order/create:a1b2c3）
 * - 长度可控：参数用 MD5 摘要，固定 32 位
 *
 * 【为什么用 MD5 而不是原始参数】
 * - 参数可能很大（如上传文件的 DTO），直接拼接会导致 key 过长
 * - MD5 固定 32 位，且相同输入产生相同输出，满足幂等需求
 * - MD5 不可逆，避免敏感参数明文暴露在 Redis key 中
 *
 * @author litisle
 */
public class IdempotentKeyBuilder {

    /**
     * Key 前缀（统一管理，避免冲突）
     */
    private static final String IDEMPOTENT_KEY_PREFIX = "idempotent:";

    /**
     * 构建幂等 key
     *
     * @param userId      用户ID（未登录传 null，内部转为 "anonymous"）
     * @param methodPath  接口路径（如 OrderController:createOrder）
     * @param args        方法参数（用于计算摘要）
     * @param includeArgs 是否将参数纳入 key
     * @return Redis key，格式：idempotent:{userId}:{methodPath}:{argsMd5}
     */
    public String build(Long userId, String methodPath, Object[] args, boolean includeArgs) {
        // 1. 用户标识（null 转为 anonymous）
        String userKey = (userId != null) ? String.valueOf(userId) : "anonymous";

        // 2. 计算参数摘要
        String argsDigest = "";
        if (includeArgs && args != null && args.length > 0) {
            StringBuilder sb = new StringBuilder();
            for (Object arg : args) {
                sb.append(arg != null ? arg.toString() : "null").append("|");
            }
            argsDigest = DigestUtils.md5DigestAsHex(sb.toString().getBytes(StandardCharsets.UTF_8));
        }

        // 3. 拼接 key
        return IDEMPOTENT_KEY_PREFIX + userKey + ":" + methodPath + ":" + argsDigest;
    }
}