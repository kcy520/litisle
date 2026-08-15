package com.litisle.commonsecurity.crypto;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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
@Component
@ConfigurationProperties(prefix = "litisle.security.crypto")
public class CryptoProperties {

    /**
     * AES-256 密钥（必须 32 字符 = 256 bit）
     * 生产环境通过环境变量 LITISLE_CRYPTO_KEY 注入
     */
    private String key = "change-this-to-32-character-key-in-prod";

    /**
     * BCrypt 强度因子（默认 10，范围 4-31）
     * cost 每增加 1，计算量翻倍
     * 推荐范围：10 ~ 14，根据服务器性能调整
     */
    private int bcryptCost = 10;
}