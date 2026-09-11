package com.litisle.litisleauth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 认证模块配置属性
 *
 * 【设计说明】
 * 1. 使用 @ConfigurationProperties 将 yml 配置绑定到 JavaBean
 * 2. 配置前缀：litisle.auth
 * 3. TTL 属于运营参数，需按环境（dev/test/prod）调整，故外置到 yml
 *
 * 【yml 配置示例】
 * litisle:
 *   auth:
 *     refresh-token-expire: 604800   # Refresh Token 有效期（秒），默认 7 天
 *
 * @author litisle
 */
@Data
@Component
@ConfigurationProperties(prefix = "litisle.auth")
public class AuthProperties {

    /** Refresh Token 有效期（秒），默认 7 天（604800 秒） */
    //默认值，只有 yml 里没配这个 key 时才会用
    private long refreshTokenExpire = 604800L;

    /** 单用户最大在线设备数，超限自动踢出最旧设备，默认 5 台 */
    private int maxDevices = 5;
}
