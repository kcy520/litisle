package com.litisle.litisleapi.constant;

/**
 * Feign 超时、重试、上下文透传 Header 名等常量。
 *
 * 【设计说明】
 * 1. 超时/重试常量：与 FeignApiConfig 中的默认值保持一致，供需要覆盖默认值的客户端引用
 * 2. Header 名：统一复用 common-core 的 HeaderConstant，此处不重复定义，仅作说明
 *
 * @author litisle
 */
public class FeignConstant {

    // ==================== 超时（毫秒） ====================

    /** 连接超时（毫秒） */
    public static final int CONNECT_TIMEOUT_MILLIS = 5000;

    /** 读取超时（毫秒） */
    public static final int READ_TIMEOUT_MILLIS = 10000;

    // ==================== 重试 ====================

    /** 最大重试次数（不含首次请求） */
    public static final int MAX_RETRY = 3;

    /** 重试间隔（毫秒） */
    public static final long RETRY_INTERVAL_MILLIS = 1000L;

    // ==================== 上下文透传 Header ====================
    // 说明：Header 名称统一使用 common-core 的 HeaderConstant
    //  - HeaderConstant.USER_ID      → X-User-Id
    //  - HeaderConstant.USERNAME     → X-Username
    //  - HeaderConstant.USER_ROLES   → X-Roles
    //  - HeaderConstant.PERMISSIONS  → X-User-Permissions
    //  - HeaderConstant.TRACE_ID     → X-Trace-Id
}
