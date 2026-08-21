package com.litisle.litisleauth.service.impl;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commonredis.constant.CacheConstant;
import com.litisle.commonredis.util.RedisUtil;
import com.litisle.litisleauth.constant.AuthCacheKey;
import com.litisle.litisleauth.service.CaptchaService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 验证码服务实现
 *
 * 【流程】
 * 发送：生成验证码 → Redis 存储 → 发送邮件
 * 校验：读取 Redis → 比对 → 删除（一次性）
 *
 * 【注意】ErrorCode 中的 TOO_MANY_REQUESTS / CAPTCHA_ERROR / MAIL_SEND_ERROR
 * 请核对 common-core 中实际枚举名，如有出入请替换
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CaptchaServiceImpl implements CaptchaService {

    private final RedisUtil redisUtil;
    private final JavaMailSender mailSender;

    @Value("${spring.mail.from:}")
    private String mailFrom;

    /** 图形验证码 TTL（秒）：3 分钟 */
    private static final long IMG_CODE_TTL = 3 * 60L;

    /** 邮箱验证码 TTL（秒）：5 分钟 */
    private static final long EMAIL_CODE_TTL = 5 * 60L;

    /** 防刷窗口 TTL（秒）：1 分钟 */
    private static final long LIMIT_TTL = 60L;

    /** 每分钟最大发送次数 */
    private static final int LIMIT_MAX = 5;

    /**
     * 发送邮箱验证码
     * @param email 目标邮箱
     * @param ip    客户端 IP（防刷）
     */
    @Override
    public void sendEmailCode(String email, String ip) {
        // 1. 防刷校验：同一 IP 每分钟最多 5 次
        String limitKey = CacheConstant.CAPTCHA_EMAIL_LIMIT + ip;
        Long count = redisUtil.increment(limitKey, 1);
        if (count != null && count == 1) {
            redisUtil.expire(limitKey, LIMIT_TTL, TimeUnit.SECONDS);
        }
        if (count != null && count > LIMIT_MAX) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS);
        }
        // 2. 生成 6 位数字验证码
        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1000000));
        // 3. 存储 Redis（5 分钟有效）
        redisUtil.set(CacheConstant.CAPTCHA_EMAIL + email, code, EMAIL_CODE_TTL, TimeUnit.SECONDS);
        // 4. 发送邮件
        sendMail(email, code);
        log.info("邮箱验证码已发送 email:{}", email);
    }

    /**
     * 图像验证码
     * @param uuid 图形验证码标识
     * @param code 用户输入的验证码
     */
    @Override
    public void verifyImgCode(String uuid, String code) {
        String key = CacheConstant.CAPTCHA_IMG + uuid;
        Object cached = redisUtil.get(key);
        if (cached == null || !cached.toString().equalsIgnoreCase(code)) {
            throw new BizException(ErrorCode.CAPTCHA_ERROR);
        }
        // 校验通过后立即删除（一次性）
        redisUtil.delete(key);
    }

    /**
     * 验证邮箱验证码
     * @param email 邮箱
     * @param code  用户输入的验证码
     */
    @Override
    public void verifyEmailCode(String email, String code) {
        String key = CacheConstant.CAPTCHA_EMAIL + email;
        Object cached = redisUtil.get(key);
        if (cached == null || !cached.toString().equals(code)) {
            throw new BizException(ErrorCode.CAPTCHA_ERROR);
        }
        // 校验通过后立即删除（一次性）
        redisUtil.delete(key);
    }

    /**
     * 发送邮件
     */
    private void sendMail(String to, String code) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(mailFrom);
            helper.setTo(to);
            helper.setSubject("LitIsle 邮箱验证码");
            helper.setText("您的验证码是：<b>" + code + "</b>，5 分钟内有效，请勿泄露给他人。", true);
            mailSender.send(message);
        } catch (MessagingException e) {
            log.error("发送邮箱验证码失败 to:{}", to, e);
            throw new BizException(ErrorCode.MAIL_SEND_ERROR);
        }
    }
}