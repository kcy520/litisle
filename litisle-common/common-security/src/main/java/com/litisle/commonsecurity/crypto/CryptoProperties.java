package com.litisle.commonsecurity.crypto;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 数据加密配置类
 *
 * 【设计说明】
 * 1. 使用 @ConfigurationProperties 将 yml 配置绑定到 JavaBean
 * 2. 配置前缀：litisle.security.crypto
 * 3. 支持 AES-256-GCM 密钥和 BCrypt cost 因子配置
 * 4. 生产环境密钥通过环境变量注入，不硬编码
 *
 * 【yml 配置示例】
 * litisle:
 *   security:
 *     crypto:
 *       key: ${LITISLE_CRYPTO_KEY:change-this-to-32-character-key-in-prod}
 *       bcrypt-cost: 10
 *
 * @author litisle
 */
@Data
@ConfigurationProperties(prefix = "litisle.security.crypto")
public class CryptoProperties {

        /**
     * AES-256 密钥（Base64 编码的 32 字节密钥 = 256 bit）
     * todo 生产环境通过环境变量 LITISLE_CRYPTO_KEY 注入
     * 无默认值，必须通过 yml 配置 litisle.security.crypto.key 提供
     * 未配置时 @ConditionalOnProperty 会跳过 Aes256GcmCrypto Bean 创建，不会报错
     * 【注意】Java 默认值不能设为非 Base64 字符串，否则 Base64 解码会抛出异常
     */
    private String key;

    /**
     * BCrypt 强度因子（默认 10，范围 4-31）
     * cost 每增加 1，计算量翻倍
     * 推荐范围：10 ~ 14，根据服务器性能调整
     */
    private int bcryptCost = 10;
}