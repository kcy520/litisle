package com.litisle.commonsecurity.jwt;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置属性类
 *
 * 【设计说明】
 * 1. 使用 @ConfigurationProperties 将 yml 配置绑定到 JavaBean
 * 2. 配置前缀：litisle.security.jwt
 * 3. 支持密钥、有效期等核心参数配置
 * 4. 生产环境密钥通过环境变量注入，不硬编码
 *
 * 【yml 配置示例】
 * litisle:
 *   security:
 *     jwt:
 *       secret: ${JWT_SECRET:your-256-bit-secret-key-here}
 *       access-token-expire: 1800
 *       remember-me-expire: 604800
 *
 * @author litisle
 */
@Data
@ConfigurationProperties(prefix = "litisle.security.jwt")
public class JwtProperties {

    /**
     * JWT 签名密钥（HMAC-SHA256，至少 256 bit = 32 字符）
     *
     * 生产环境必须通过环境变量 JWT_SECRET 注入
     * 默认值仅用于开发调试，生产环境务必修改
     * 密钥泄露后果：攻击者可伪造任意用户身份的令牌
     */
    //todo 后续改为环境变量
    private String secret = "LitIsle-Default-JWT-Secret-Key-Change-In-Production!";

    /**
     * Access Token 有效期（秒），默认 30 分钟
     *
     * 短有效期降低令牌泄露风险
     * 配合 Refresh Token 实现无感续期
     */
    private long accessTokenExpire = 1800;

    /**
     * 记住我模式 Access Token 有效期（秒），默认 7 天
     *
     * 登录接口接收 rememberMe 参数
     * 为 true 时使用此 TTL
     */
    private long rememberMeExpire = 604800;
}