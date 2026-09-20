package com.litisle.commonsecurity.crypto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
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
 * CryptoAutoConfiguration 里新增了一个 HMAC Bean 的创建逻辑：启动 Spring 时，决定“HMAC 摘要用的密钥从哪来”，要么用你独立配的 hashKey，要么从 AES 主密钥派生一个。派生算法是确定性的，所以重启、重新部署都不会变。
关键：@ConditionalOnProperty(name = "litisle.crypto.key")
一句话：AES 和 HMAC 是“绑定的”，有就都有，没有就都没有，行为一致，不会半套能用半套不能用。

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
     * 新增 HMAC Bean + 子密钥派生
     * 
     * CryptoAutoConfiguration 里新增了一个 HMAC Bean 的创建逻辑：启动 Spring 时，决定“HMAC 摘要用的密钥从哪来”，要么用你独立配的 hashKey，要么从 AES 主密钥派生一个。派生算法是确定性的，所以重启、重新部署都不会变。
要 litisle.crypto.key 这个配置存在，AES 和 HMAC 两个 Bean 都会创建；不存在，两个都不创建。
AES 和 HMAC 是“绑定的”，有就都有，没有就都没有，行为一致，不会半套能用半套不能用。
 🔐 AES（这里用的是 AES-256-GCM）一句话：把东西锁进保险箱，能原样取出来。phone / email

 HMAC-SHA256    phone_hash / email_hash   用户phone和email字段的索引，aes加密每次结果不同，所以需要解密才能比对，效率极低
一句话：给东西盖个"指纹章"，章能验证但不能还原出原物。
- 作用 ：优先用独立`hashKey` ；没配则从 AES 主密钥确定性派生子密钥（`deriveSubkey` ：主密钥对固定标签`litisle:hmac-subkey:v1` 做一次 HMAC）。密钥不足 32 字节直接拒绝启动（Fail-Fast）。
     * 为什么 ：
- 放在 同一个自动配置类 里，跟随`key` 的存在条件——auth 服务不配 AES key 时，AES 和 HMAC 都不创建，行为一致
- 派生而非硬编码默认值 ：派生是确定性的（主密钥不变 → 子密钥不变），历史数据不受影响，且比“默认密钥写死在代码里”安全（反编译即知默认密钥）；
- 启动日志打印走的是哪条密钥路径，方便你部署生产时确认“独立 hashKey 是否真的生效了”。

     *  创建 HMAC-SHA256 摘要工具 Bean
     *
     * 用途：phone_hash/email_hash 等确定性摘要列（唯一索引、查重）
     * 密钥优先级：独立配置的 hashKey > 从 AES 主密钥派生的子密钥
     * 跟随 AES Bean 的创建条件（key 已配置），Fail-Fast 校验密钥长度
     *
     * @param properties 加密配置
     * @return HmacSha256Crypto 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "litisle.security.crypto", name = "key")
    public HmacSha256Crypto hmacSha256Crypto(CryptoProperties properties) {
        byte[] hmacKeyBytes;
        String hashKey = properties.getHashKey();
        if (hashKey != null && !hashKey.isBlank()) {
            hmacKeyBytes = Base64.getDecoder().decode(hashKey);
            log.info("HMAC-SHA256 使用独立配置的 hashKey");
        } else {
            hmacKeyBytes = deriveSubkey(Base64.getDecoder().decode(properties.getKey()));
            log.info("hash-key 未独立配置，已从主密钥派生 HMAC 子密钥");
        }

        if (hmacKeyBytes.length < 32) {
            throw new IllegalStateException(
                    "HMAC 密钥必须 ≥ 32 字节，当前为 " + hmacKeyBytes.length + " 字节");
        }

        log.info("HMAC-SHA256 摘要初始化完成");
        return new HmacSha256Crypto(hmacKeyBytes);
    }

    /**
     * 从 AES 主密钥派生 HMAC 子密钥（HKDF 思路的简化版）
     * 使用主密钥对固定标签做一次 HMAC，实现密钥用途分离
     *
     * @param masterKey AES 主密钥字节
     * @return 32 字节 HMAC 子密钥
     */
    private static byte[] deriveSubkey(byte[] masterKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(masterKey, "HmacSHA256"));
            return mac.doFinal("litisle:hmac-subkey:v1".getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC 子密钥派生失败", e);
        }
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