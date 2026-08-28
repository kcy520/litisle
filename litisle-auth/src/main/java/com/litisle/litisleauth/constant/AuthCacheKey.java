package com.litisle.litisleauth.constant;

/**
 * auth 模块 Redis Key 常量
 *
 * 【设计说明】
 * 1. 验证码相关 Key 已提升为 common-redis CacheConstant 公共常量（captcha: 前缀）
 * 2. 本类仅保留 auth 模块私有 Key
 */
public final class AuthCacheKey {

    private AuthCacheKey() {
    }

    /** 刷新令牌：auth:refresh:{refreshToken} → userId，TTL 7 天 */
    public static final String REFRESH_TOKEN = "auth:refresh:";

    /** 用户→刷新令牌映射：auth:refresh:user:{userId} → refreshToken，便于退出时清理 */
    public static final String USER_REFRESH = "auth:refresh:user:";
}