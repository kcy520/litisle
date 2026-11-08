package com.litisle.commoncore.constant;

/**
 * 客户端 IP 解析常量类
 *
 * 【设计说明】
 * 1. 集中管理 IP 解析过程中跨服务共用的固定值，避免各模块重复硬编码
 * 2. 所有服务使用同一个兜底值，保证同一请求在不同模块得到相同的 IP 标识
 *
 * @author litisle
 */
public class ClientIpConstant {

    /**
     * 客户端 IP 无法解析时的统一兜底值
     * 用于 IP 维度限流、幂等、浏览去重等场景，避免空串或各模块自定义值导致口径不一致
     */
    public static final String UNKNOWN_IP = "unknown";
}
