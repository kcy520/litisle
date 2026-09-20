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
 * 【启动阶段】
业务服务（litisle-user）启动
  → Spring 加载 application.yml + 当前激活 profile 的 yaml
  → 后加载的 profile 覆盖同名键 → litisle.security.crypto.key = 硬编码值（local）
  → CryptoAutoConfiguration 读 CryptoProperties
  → @ConditionalOnProperty 检查 key 存在
       ├─ 存在 → Base64 解码 + 256bit 校验（Fail-Fast，错误直接拒绝启动）
       │          → 创建 Aes256GcmCrypto Bean
       └─ 不存在 → 跳过 AES Bean（如 auth 服务不配 key 就没有）
  → PasswordCrypto 无条件创建（bcrypt-cost=10）

【运行阶段 —— 加密】
注册请求带明文手机号
  → 注入 Aes256GcmCrypto（业务服务内已装配好）
  → encrypt(): SecureRandom 生成 12B IV → GCM 加密 → [IV|密文+标签] → Base64
  → 密文入 sys_user.phone

【运行阶段 —— 解密/展示】
个人中心查资料
  → decrypt(): Base64 解码 → 拆 IV → GCM 验标签解密
       ├─ 标签通过 → 明文 → 脱敏 138****1234
       └─ 被篡改 → AEADBadTagException → BizException(ENCRYPTION_ERROR)
 */

        /**
     * AES-256 密钥（Base64 编码的 32 字节密钥 = 256 bit）
     * todo 生产环境通过环境变量 LITISLE_CRYPTO_KEY 注入
     * 无默认值，必须通过 yml 配置 litisle.security.crypto.key 提供
     * 未配置时 @ConditionalOnProperty 会跳过 Aes256GcmCrypto Bean 创建，不会报错
     * 【注意】Java 默认值不能设为非 Base64 字符串，否则 Base64 解码会抛出异常
     */
    private String key;

    /**
     * 新增`hashKey` 配置项

- 作用 ：给 HMAC-SHA256 摘要提供独立密钥入口（`litisle.security.crypto.hash-key` ）。
- 为什么 ：企业规范要求 密钥按用途分离 。AES 密钥泄露只损密文列，HMAC 密钥泄露只损摘要列；共用一把钥匙等于“单点失守、双杀全库”。设为可选，是让你本地零配置也能跑。
     * HMAC-SHA256 密钥（Base64 编码，≥32 字节）——可选
     * 用途：phone_hash/email_hash 等确定性摘要列（唯一索引、查重），与 AES 密钥用途分离
     * 未配置时由 CryptoAutoConfiguration 从 AES 主密钥派生子密钥（本地开发零配置可用）
     * 生产环境推荐独立配置，避免一把密钥泄露导致密文与摘要同时被攻破
     */
    private String hashKey;

    /**
     * BCrypt 强度因子（默认 10，范围 4-31）
     * cost 每增加 1，计算量翻倍
     * 推荐范围：10 ~ 14，根据服务器性能调整
     */
    private int bcryptCost = 10;
}