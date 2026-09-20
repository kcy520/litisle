package com.litisle.commonsecurity.crypto;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 密码加密工具类（BCrypt 封装）
 *
 * 【设计说明】
 * 1. 封装 Spring Security 的 BCryptPasswordEncoder，提供统一入口
 * 2. 用户密码使用 BCrypt 哈希，不可逆，只验不解密
 * 3. 自动加盐，每个密码生成随机盐，防止彩虹表攻击
 * 4. cost 因子可配置，默认 10
 *
 * 【输出格式】
 * $2a${cost}${22字符盐}${31字符哈希}
 * 示例：$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH
 *
 * 【与 AES-256-GCM 的区别】
 * - BCrypt：不可逆，用于密码存储（只验不解密）
 * - AES-256-GCM：可逆，用于手机号/邮箱等需要展示的字段
 *
 * 【为什么封装】
 * 1. 解耦：业务代码不直接依赖 BCryptPasswordEncoder，未来换 Argon2/scrypt 只需改内部
 * 2. 统一入口：团队只需知道 encode()/matches()，不需要了解 Spring Security API
 * 3. 扩展点：如需加密码强度校验、历史密码去重，直接在 encode() 里加，不用改业务代码
 *
 * @author litisle
 */
public class PasswordCrypto {

    /**
     * BCrypt 密码流程（PasswordCrypto）
     * 与 AES 完全独立的一条链路：

- `encode("123")` ：自动生成 22 字符随机盐 → cost=10 轮哈希 → 输出`$2a$10$盐+哈希` （60 字符，你种子数据里那个就是）；
- `matches("输入的密码", 库里的哈希)` ：从哈希串里 提取盐和 cost → 用同样参数重算一遍 → 恒定时间比较（防时序攻击）；
- 只验不解 ：数据库泄露也拿不回明文密码（AES 列泄露配合密钥泄露则可解，所以密钥管理等级不同）
设计上特意封装了一层（不直接暴露`BCryptPasswordEncoder` ），注释里写明了动机：未来换 Argon2 只改这个类内部。。
     */
    private final BCryptPasswordEncoder passwordEncoder;

    /**
     * 构造器：使用默认 cost 因子（10）
     * 推荐默认值，在安全性和性能之间取得平衡
     */
    public PasswordCrypto() {
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    /**
     * 构造器：指定 cost 因子
     *
     * @param strength cost 因子（4-31），越大越安全但越慢
     *                 推荐范围：10 ~ 14
     *                 每增加 1，计算量翻倍
     */
    public PasswordCrypto(int strength) {
        this.passwordEncoder = new BCryptPasswordEncoder(strength);
    }

    /**
     * 加密密码（BCrypt 哈希）
     *
     * 【流程】
     * 1. 自动生成随机盐（22 字符）
     * 2. 使用 cost 因子和盐对密码进行 BCrypt 哈希
     * 3. 返回格式化的哈希字符串（包含盐、cost、哈希值）
     *
     * @param rawPassword 明文密码
     * @return BCrypt 哈希字符串
     */
    public String encode(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    /**
     * 验证密码是否匹配
     *
     * 使用恒定时间比较，防止时序攻击
     * 从哈希字符串中提取盐和 cost，重新计算哈希后比较
     *
     * @param rawPassword     明文密码（用户输入）
     * @param encodedPassword BCrypt 哈希字符串（数据库存储）
     * @return true=匹配，false=不匹配
     */
    public boolean matches(String rawPassword, String encodedPassword) {
        return passwordEncoder.matches(rawPassword, encodedPassword);
    }
}