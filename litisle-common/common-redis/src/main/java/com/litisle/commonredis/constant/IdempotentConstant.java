package com.litisle.commonredis.constant;

import com.litisle.commoncore.constant.ClientIpConstant;

/**
 * 幂等请求业务常量类
 *
 * 【设计说明】
 * 1. 集中管理幂等切面（@Idempotent）用到的业务常量，避免硬编码
 * 2. 缓存 key 前缀见 CacheConstant.IDEMPOTENT，本类存放 key 值 / 标识 / 分隔符等
 *
 * @author litisle
 */
public class IdempotentConstant {

    /**
     * SETNX 幂等占位值（key 的 value 仅作存在标记）
     */
    public static final String IDEMPOTENT_VALUE = "1";

    /**
     * 匿名用户标识前缀（规避不同匿名用户共用 "anonymous" 导致互相误伤）
     */
    public static final String ANONYMOUS_PREFIX = "anonymous:";

    /**
     * 客户端 IP 未知标识（无 HTTP 环境或解析失败时使用）
     * 值统一引用 {@link ClientIpConstant#UNKNOWN_IP}，保留旧名仅为兼容现有调用方，避免两份字面量各自漂移
     */
    public static final String UNKNOWN_IP = ClientIpConstant.UNKNOWN_IP;

    /**
     * IP 列表分隔符（X-Forwarded-For 可能携带多级代理）
     */
    public static final String IP_LIST_SEPARATOR = ",";
}
