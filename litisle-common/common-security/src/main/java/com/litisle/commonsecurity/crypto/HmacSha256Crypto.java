package com.litisle.commonsecurity.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 新增。。。
 * - 作用 ：`digest(明文) → 64 位 hex` ，同一明文恒定同一输出——这正是`phone_hash` /`email_hash` 唯一索引能生效的前提（AES 随机 IV 做不到）。
- - 为什么必须 HMAC 而非`MessageDigest` ：手机号/常见邮箱取值空间太小，纯 SHA-256 可以彩虹表反推；HMAC 引入密钥后，拿到库没拿到密钥也推不出明文。
 * HmacSha256Crypto 是一个工具类，唯一职责：把手机号/邮箱这样的明文，算成一个"带密钥的、不可逆的、确定性的 64 位十六进制指纹"，存进 phone_hash / email_hash 列。
 * 
 * HMAC-SHA256 确定性摘要工具类
 *
 * 【设计说明】
 * 1. 与 Aes256GcmCrypto（可逆）互补：本类输出确定性摘要，用于唯一索引与查重
 * 2. 必须使用 HMAC 而非普通 SHA-256：手机号/邮箱的取值空间小，
 *    纯哈希可被彩虹表反推，HMAC 引入密钥后攻击者无密钥则无法反推明文
 * 3. 输出 64 位十六进制字符串，对应 varchar(64) 的 *_hash 列
 * 4. 确定性：同一明文恒定输出同一摘要（与 AES-GCM 随机 IV 相反），
 *    这是唯一索引 uk_phone_hash_del / uk_email_hash_del 能生效的前提
 *
 * 【与 AES-256-GCM 的分工】
 * - AES-256-GCM：密文入 phone/email 列（可解密展示、脱敏）
 * - HMAC-SHA256：摘要入 phone_hash/email_hash 列（唯一索引、查重、换绑校验）
 *
 * @author litisle
 */
public class HmacSha256Crypto {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec keySpec;

    /**
     * 构造器：传入 HMAC 密钥字节
     * 密钥来源见 CryptoAutoConfiguration：独立 hashKey 或从 AES 主密钥派生
     *
     * @param keyBytes HMAC 密钥（≥32 字节，由自动配置类校验）
     */
    public HmacSha256Crypto(byte[] keyBytes) {
        this.keySpec = new SecretKeySpec(keyBytes, ALGORITHM);
    }

    /**
     * 计算确定性摘要
     *
     * @param plainText 明文（如手机号、邮箱）
     * @return 64 位十六进制摘要字符串
     */
    public String digest(String plainText) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(keySpec);
            byte[] raw = mac.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return hex(raw);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 计算失败", e);
        }
    }

    /**
     * 字节数组转十六进制字符串（小写）
     */
    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}