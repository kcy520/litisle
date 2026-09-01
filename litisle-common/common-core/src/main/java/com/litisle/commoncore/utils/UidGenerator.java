package com.litisle.commoncore.utils;

import java.security.SecureRandom;

/**
 * UID 生成器
 * <p>
 * 生成对外暴露且全局唯一的用户标识（UID），由两段信息组合而成，总空间不超过 49 个二进制位，
 * 转成十进制后最多 15 位数字：
 * <pre>
 * ┌──────────────────────────┬──────────────────────────┐
 * │     前段：相对秒时间戳     │     后段：随机序列        │
 * │   （从项目上线那一刻起算）  │   （每次生成时随机取一个） │
 * └──────────────────────────┴──────────────────────────┘
 * </pre>
 *
 * 【设计说明】
 * 1. 前段占 32 位：记录「现在距离项目上线过去了多少秒」，放在高位，数值比较时先比时间，
 *    天然保证「数值大的注册更晚」
 * 2. 后段占 17 位：记录一个 0 到 131071 之间的随机整数，放在低位，把同一秒内生成的 UID 打散，
 *    让它们不连续，避免被枚举
 * 3. 两段拼成一个整数，再转成十进制字符串，即为最终对外暴露的 UID
 * 4. 使用 {@link SecureRandom} 安全随机数，保证不可预测
 *
 * 【使用场景】
 * - 用户注册时生成对外 UID，作为用户间搜索的唯一标识
 * - 对外接口只暴露 UID，不暴露数据库自增主键 id，防止被遍历爬取
 *
 * @author litisle
 */
public class UidGenerator {

    /**
     * 随机序列位数（17 位）
     */
    private static final int RANDOM_BITS = 17;

    /**
     * 随机序列最大值（2^17 - 1 = 131071）
     */
    private static final int RANDOM_MAX = (1 << RANDOM_BITS) - 1;

    /**
     * 项目上线时间（epoch），从该时刻起算相对秒
     * <p>
     * 默认取 2026-01-01 00:00:00 UTC（毫秒时间戳 1767225600000）。
     * 上线前请根据实际项目上线时间调整，保证 UID 从上线时刻起算。
     */
    private static final long DEFAULT_EPOCH_MILLIS = 1767225600000L;

    /**
     * 安全随机数（线程安全）
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * 私有构造器，禁止实例化
     */
    private UidGenerator() {
    }

    /**
     * 生成 UID（使用默认项目上线时间）
     *
     * @return 十进制字符串形式的 UID，最多 15 位数字
     */
    public static String generate() {
        return generate(DEFAULT_EPOCH_MILLIS);
    }

    /**
     * 生成 UID（自定义项目上线时间）
     *
     * @param epochMillis 项目上线时间（毫秒时间戳），从该时刻起算相对秒
     * @return 十进制字符串形式的 UID，最多 15 位数字
     */
    public static String generate(long epochMillis) {
        // 1. 前段：相对秒时间戳（32 位，放在高位）
        long seconds = (System.currentTimeMillis() - epochMillis) / 1000;
        if (seconds < 0) {
            throw new IllegalArgumentException("项目上线时间(epochMillis)不能晚于当前时间");
        }
        // 掩码保证前段严格占 32 位（2^32 秒 ≈ 136 年，足够业务使用）
        seconds &= 0xFFFFFFFFL;

        // 2. 后段：随机序列（17 位，放在低位），打散同一秒内的 UID
        long random = SECURE_RANDOM.nextInt(RANDOM_MAX + 1);

        // 3. 两段拼接：前段左移 17 位 + 后段
        long uid = (seconds << RANDOM_BITS) | random;

        return Long.toString(uid);
    }
}