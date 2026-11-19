package com.litisle.litisleauth.utils;

import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 设备指纹提取工具
 *
 * <p>【算法】User-Agent + "|" + Accept-Language 拼接后取 SHA-256 十六进制（64 位小写）。
 * 头缺失按空串参与拼接——同一客户端头部组合稳定，指纹即稳定。</p>
 *
 * <p>【定位】弱指纹：只作盗用检测信号（刷新时比对，不一致吊销令牌族），不作强身份认证；
 * UA/语言可伪造，但可堵「token 被窃取后异环境离线独用」的空窗。</p>
 *
 * @author litisle
 */
public final class DeviceFingerprintUtil {

    private DeviceFingerprintUtil() {
    }

    /**
     * 从请求头提取设备指纹
     *
     * @param request 当前 HTTP 请求
     * @return SHA-256 十六进制指纹（64 位小写）
     */
    public static String fingerprint(HttpServletRequest request) {
        // 1. 头缺失按空串参与拼接：同一客户端头部组合稳定，指纹即稳定
        String userAgent = orEmpty(request.getHeader("User-Agent"));
        String language = orEmpty(request.getHeader("Accept-Language"));
        String raw = userAgent + "|" + language;

        // 2. SHA-256 摘要（JDK 必含该算法，缺失视为环境异常直接抛出）
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }

        // 3. 摘要转小写十六进制
        byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * 空值归一化
     *
     * @param value 请求头值，可能为 null 或空白
     * @return null/空白返回空串，否则返回原值
     */
    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
