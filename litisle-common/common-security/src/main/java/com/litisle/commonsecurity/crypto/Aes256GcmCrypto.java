package com.litisle.commonsecurity.crypto;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 加密/解密工具类
 *
 * 【设计说明】
 * 1. 使用 JDK 标准 javax.crypto API，无需第三方依赖
 * 2. GCM 模式提供 AEAD（Authenticated Encryption with Associated Data）能力
 * 3. 每次加密生成随机 12 字节 IV，相同明文每次输出不同密文
 * 4. 输出格式：Base64(IV + 密文 + 认证标签)，便于数据库存储
 * 5. 认证标签 16 字节，确保密文完整性
 *
 * 【算法参数】
 * - 算法：AES/GCM/NoPadding
 * - 密钥长度：256 bit
 * - IV 长度：12 字节（NIST 推荐）
 * - 认证标签长度：16 字节（128 bit）
 * - 输出编码：Base64
 *
 * 【安全要点】
 * - 解密时 Cipher 自动验证认证标签，篡改立即抛出 AEADBadTagException
 * - 不直接暴露初始化方法，通过构造器注入密钥
 * - 密钥不参与日志打印，避免意外泄露
 *
 * @author litisle
 */
@Slf4j
public class Aes256GcmCrypto {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128; // 16 字节
    private static final int KEY_SIZE = 256;

    private final SecretKey secretKey;

    /**
     * 构造器：通过 Base64 编码的密钥字符串初始化
     *
     * @param base64Key Base64 编码的 AES-256 密钥
     */
    public Aes256GcmCrypto(String base64Key) {
        byte[] decoded = Base64.getDecoder().decode(base64Key);
        if (decoded.length * 8 != KEY_SIZE) {
            throw new IllegalArgumentException(
                    "AES-256 密钥必须为 256 bit（32 字节），当前为 " + (decoded.length * 8) + " bit");
        }
        this.secretKey = new SecretKeySpec(decoded, "AES");
    }

    /**
     * 构造器：通过原始密钥字节数组初始化
     *
     * @param keyBytes AES-256 密钥字节数组（必须 32 字节）
     */
    public Aes256GcmCrypto(byte[] keyBytes) {
        if (keyBytes.length * 8 != KEY_SIZE) {
            throw new IllegalArgumentException(
                    "AES-256 密钥必须为 256 bit（32 字节），当前为 " + (keyBytes.length * 8) + " bit");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * 构造器：通过 SecretKey 对象初始化
     *
     * @param secretKey AES 密钥
     */
    public Aes256GcmCrypto(SecretKey secretKey) {
        this.secretKey = secretKey;
    }

    /**
     * 加密明文
     *
     * 【流程】
     * 1. 生成 12 字节随机 IV
     * 2. 使用密钥和 IV 初始化 Cipher（ENCRYPT_MODE）
     * 3. 加密明文，得到密文 + 认证标签
     * 4. Base64 编码 IV + 密文 + 认证标签
     * 5. 返回 Base64 字符串
     *
     * @param plainText 明文（UTF-8 编码）
     * @return Base64 编码的密文（IV + 密文 + 认证标签）
     * @throws BizException 加密失败时抛出 ENCRYPTION_ERROR
     */
    public String encrypt(String plainText) {
        try {
            byte[] iv = generateIv();
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);

            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // 合并 IV + 密文（cipherText 已包含认证标签）
            byte[] combined = new byte[IV_LENGTH + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(cipherText, 0, combined, IV_LENGTH, cipherText.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("AES-256-GCM 加密失败", e);
            throw new BizException(ErrorCode.ENCRYPTION_ERROR, "加密失败: " + e.getMessage());
        }
    }

    /**
     * 解密密文
     *
     * 【流程】
     * 1. Base64 解码，提取 IV（前 12 字节）和密文（剩余部分）
     * 2. 使用密钥和 IV 初始化 Cipher（DECRYPT_MODE）
     * 3. 解密并验证认证标签
     * 4. 返回明文字符串
     *
     * @param encryptedData Base64 编码的密文（IV + 密文 + 认证标签）
     * @return 解密后的明文字符串
     * @throws BizException 解密失败时抛出 ENCRYPTION_ERROR
     */
    public String decrypt(String encryptedData) {
        try {
            byte[] decoded = Base64.getDecoder().decode(encryptedData);

            // 提取 IV 和密文
            byte[] iv = new byte[IV_LENGTH];
            byte[] cipherText = new byte[decoded.length - IV_LENGTH];
            System.arraycopy(decoded, 0, iv, 0, IV_LENGTH);
            System.arraycopy(decoded, IV_LENGTH, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

            byte[] plainText = cipher.doFinal(cipherText);
            return new String(plainText, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("AES-256-GCM 解密失败", e);
            throw new BizException(ErrorCode.ENCRYPTION_ERROR, "解密失败: " + e.getMessage());
        }
    }

    /**
     * 判断明文是否与加密后的密文匹配（用于手机号/邮箱登录比对）
     *
     * 先解密密文，再与明文比较，避免"密文-密文"比较的误区
     * 因为 AES-256-GCM 每次加密结果不同，不能直接比较密文
     *
     * @param plainText     明文（用户输入的手机号/邮箱）
     * @param encryptedData 已加密存储的密文
     * @return true=匹配，false=不匹配
     */
    public boolean matches(String plainText, String encryptedData) {
        try {
            String decrypted = decrypt(encryptedData);
            return decrypted.equals(plainText);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 生成随机 IV（Initialization Vector）
     *
     * 使用 SecureRandom 生成 12 字节随机数
     * NIST SP 800-38D 建议 GCM 模式的 IV 长度为 12 字节
     */
    private byte[] generateIv() {
        byte[] iv = new byte[IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        return iv;
    }

    /**
     * 生成随机 AES-256 密钥（用于密钥初始化场景）
     *
     * @return Base64 编码的 256 bit 密钥
     */
    public static String generateKey() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(KEY_SIZE);
            SecretKey key = keyGen.generateKey();
            return Base64.getEncoder().encodeToString(key.getEncoded());
        } catch (Exception e) {
            throw new RuntimeException("AES-256 密钥生成失败", e);
        }
    }
}