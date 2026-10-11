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

    /** 刷新令牌：auth:refresh:{refreshToken} → userId，TTL 7 天
     * refreshToken → 用户信息的映射
     * */
    public static final String REFRESH_TOKEN = "auth:refresh:";

    /** 用户→刷新令牌映射：auth:refresh:user:{userId} → refreshToken，便于退出时清理
     * 用户 → refreshToken 的映射
     * */
    public static final String USER_REFRESH = "auth:refresh:user:";

    /** 设备会话：auth:device:{deviceId} → Hash {userId, tokenValue, deviceName, ip, loginTime, lastActiveTime} */
    public static final String DEVICE_SESSION = "auth:device:";

    /** 用户设备列表：auth:user:devices:{userId} → Set<deviceId> */
    public static final String USER_DEVICES = "auth:user:devices:";

    /**
     * 禁言/封禁态快照：auth:punish:snapshot:{userId} → PunishStatusDTO JSON，短 TTL 外置可调（默认 300s±10% 抖动）
     * <p>各段服务端派生（userId 数字 ID，禁拼用户原串）；value 一律 JSON（禁 Java 原生反序列化）；
     * 覆盖式写入（撤销=全空快照覆盖=同步解封），无独立 delete 分支</p>
     */
    public static final String PUNISH_SNAPSHOT = "auth:punish:snapshot:";

    /**
     * 设备会话 Hash 字段名常量
     * <p>
     * 与 DEVICE_SESSION Hash 结构一一对应，供读写双方共用，避免拼写不一致。
     */
    public static final class DeviceHashField {
        private DeviceHashField() {}

        /** 用户 ID */
        public static final String USER_ID = "userId";
        /** 设备 token 值（用于踢出时下线对应会话） */
        public static final String TOKEN_VALUE = "tokenValue";
        /** 设备名称（如 Chrome 120.0） */
        public static final String DEVICE_NAME = "deviceName";
        /** 登录 IP */
        public static final String IP = "ip";
        /** 登录时间 (ISO) */
        public static final String LOGIN_TIME = "loginTime";
        /** 最后活跃时间 (ISO) */
        public static final String LAST_ACTIVE_TIME = "lastActiveTime";
    }
}