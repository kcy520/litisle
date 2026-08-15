package com.litisle.commonsecurity.crypto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Base64;

/**
 * 加密自动配置类
 *
 * 【设计说明】
 * 1. Aes256GcmCrypto 和 PasswordCrypto 的 Bean 创建条件分离：
 *    - Aes256GcmCrypto：仅在 litisle.security.crypto.key 已配置时创建
 *      （如 litisle-auth 不配置 key，不会创建 AES Bean，但 BCrypt 仍可用）
 *    - PasswordCrypto：始终创建（cost 默认 10，无需强制配置）
 * 2. 从 CryptoProperties 读取 AES 密钥和 BCrypt cost 配置
 * 3. 支持 @ConditionalOnMissingBean，允许业务服务覆盖默认实现
 * 4. 启动时校验密钥有效性，符合 Fail-Fast 原则
 *
 * @author litisle
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(CryptoProperties.class)
public class CryptoAutoConfiguration {

    /**
     * 创建 AES-256-GCM 加密工具 Bean
     *
     * 仅在 litisle.security.crypto.key 已配置时创建
     * 启动时校验密钥长度（256 bit = 32 字节），Fail-Fast
     *
     * @param properties 加密配置
     * @return Aes256GcmCrypto 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "litisle.security.crypto", name = "key")
    public Aes256GcmCrypto aes256GcmCrypto(CryptoProperties properties) {
        String key = properties.getKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("AES-256 密钥未配置（litisle.security.crypto.key）");
        }

        // 校验密钥格式和长度
        try {
            byte[] keyBytes = Base64.getDecoder().decode(key);
            if (keyBytes.length * 8 != 256) {
                throw new IllegalStateException(
                        "AES-256 密钥必须为 256 bit（32 字节），当前为 " + (keyBytes.length * 8) + " bit");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "AES-256 密钥不是有效的 Base64 编码: " + e.getMessage(), e);
        }

        log.info("AES-256-GCM 加密初始化完成");
        return new Aes256GcmCrypto(key);
    }

    /**
     * 创建 BCrypt 密码加密工具 Bean
     *
     * 始终创建，cost 默认 10
     * 从 CryptoProperties 读取 cost 因子，无配置则用默认值
     *
     * @param properties 加密配置
     * @return PasswordCrypto 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public PasswordCrypto passwordCrypto(CryptoProperties properties) {
        int cost = properties.getBcryptCost();
        if (cost < 4 || cost > 31) {
            log.warn("BCrypt cost 因子 {} 超出有效范围（4-31），使用默认值 10", cost);
            cost = 10;
        }

        log.info("BCrypt 密码加密初始化完成，cost = {}", cost);
        return new PasswordCrypto(cost);
    }
}