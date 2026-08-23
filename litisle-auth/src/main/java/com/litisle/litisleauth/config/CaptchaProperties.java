package com.litisle.litisleauth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 验证码配置类
 *
 * 【设计说明】
 * 1. 使用 @ConfigurationProperties 将 yml 配置绑定到 JavaBean
 * 2. 配置前缀：litisle.captcha
 * 3. TTL 与防刷阈值属于运营参数，需按环境（dev/test/prod）调整，故外置到 yml
 *
 * 【yml 配置示例】
 * litisle:
 *   captcha:
 *     img-ttl: 180        # 图形验证码 TTL（秒）
 *     email-ttl: 300      # 邮箱验证码 TTL（秒）
 *     limit-ttl: 60       # 防刷窗口 TTL（秒）
 *     limit-max: 5        # 每分钟最大发送次数
 *
 * @author litisle
 */
@Data
@Component
@ConfigurationProperties(prefix = "litisle.captcha")
public class CaptchaProperties {

    /** 图形验证码 TTL（秒），默认 3 分钟 */
    private long imgTtl = 3 * 60L;

    /** 邮箱验证码 TTL（秒），默认 5 分钟 */
    private long emailTtl = 5 * 60L;

    /** 防刷窗口 TTL（秒），默认 1 分钟 */
    private long limitTtl = 60L;

    /** 每分钟最大发送次数，默认 5 */
    private int limitMax = 5;

    /** 邮箱验证码连续校验失败上限，默认 3 次（防穷举爆破） */
    private int maxVerifyFails = 3;
}