package com.litisle.litisleauth.service.impl;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commonsecurity.desensitize.SensitiveStrategy;
import com.litisle.commonredis.constant.CacheConstant;
import com.litisle.commonredis.util.RedisUtil;
import com.litisle.litisleauth.config.CaptchaProperties;
import com.litisle.litisleauth.constant.CaptchaConstant;
import com.litisle.litisleauth.constant.MailConstant;
import com.litisle.litisleauth.domain.vo.CaptchaImgVO;
import com.litisle.litisleauth.enums.EmailCodeUsage;
import com.litisle.litisleauth.service.CaptchaService;
import com.litisle.litisleauth.utils.CaptchaCodeUtil;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 验证码服务实现
 *
 * 【流程】
 * 发送：生成验证码 → Redis 存储 → 发送邮件
 * 校验：读取 Redis → 比对 → 删除（一次性）
 *       邮箱验证码：连续失败达阈值后删除验证码，强制重新获取（防穷举爆破）
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

    /** Spring Boot 内置邮件配置（绑定 spring.mail.*，含发件人 from） */
    private final MailProperties mailProperties;

    /** 验证码配置（TTL / 防刷阈值，来自 yml litisle.captcha.*） */
    private final CaptchaProperties captchaProperties;

    /** 图形验证码生成工具类（纯技术操作） */
    private final CaptchaCodeUtil captchaCodeUtil;

    /**
     * 生成图形验证码
     */
    @Override
    public CaptchaImgVO generateImgCode() {
        // 1. 生成验证码（工具类：纯技术操作）
        CaptchaCodeUtil.CaptchaCode captchaCode = captchaCodeUtil.generate();
        // 2. 生成唯一标识
        String uuid = UUID.randomUUID().toString().replace("-", "");
        // 3. 存储 Redis（TTL 由配置 litisle.captcha.img-ttl 控制）
        redisUtil.set(CacheConstant.CAPTCHA_IMG + uuid, captchaCode.getCode(),
                captchaProperties.getImgTtl(), TimeUnit.SECONDS);
        // 4. 返回 VO（不含验证码文本，防止前端直接读取）
        return new CaptchaImgVO(uuid, captchaCode.getBase64());
    }

    /**
     * 发送邮箱验证码
     * @param email 目标邮箱
     * @param ip    客户端 IP（防刷）
     */
    @Override
    public void sendEmailCode(String email, EmailCodeUsage usage, String ip) {
        // 1. 防刷校验：同一 IP 每分钟最多 N 次（N 由配置 litisle.captcha.limit-max 控制）
        String limitKey = CacheConstant.CAPTCHA_EMAIL_LIMIT + ip;
        Long count = redisUtil.increment(limitKey, 1);
        if (count != null && count == 1) {
            redisUtil.expire(limitKey, captchaProperties.getLimitTtl(), TimeUnit.SECONDS);
        }
        if (count != null && count > captchaProperties.getLimitMax()) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS);
        }
        // 2. 生成 6 位数字验证码
        String code = String.format("%0" + CaptchaConstant.EMAIL_CODE_LENGTH + "d",
                ThreadLocalRandom.current().nextInt((int) Math.pow(10, CaptchaConstant.EMAIL_CODE_LENGTH)));
        // 3. 存储 Redis（key 带 usage，TTL 由配置 litisle.captcha.email-ttl 控制）
        redisUtil.set(buildEmailKey(usage, email), code, captchaProperties.getEmailTtl(), TimeUnit.SECONDS);
        // 4. 发送邮件
        sendMail(email, code);
        log.info("邮箱验证码已发送 email:{} usage:{}", SensitiveStrategy.EMAIL.desensitize(email), usage);
    }


    /**
     * 图像验证码校验
     * @param uuid 图形验证码标识（前端获取验证码时返回的 uuid）
     * @param code 用户输入的验证码
     */
    @Override
    public void verifyImgCode(String uuid, String code) {
        // ========== 1. 入口防御：非法参数直接拒绝，不碰 Redis ==========
        // 防止 uuid=null 拼接出 "captcha:img:null" 被利用
        // 防止 code=null 时 equalsIgnoreCase 返回 false 但语义不合法
        if (!StringUtils.hasText(uuid) || !StringUtils.hasText(code)) {
            throw new BizException(ErrorCode.CAPTCHA_ERROR);
        }

        // ========== 2. 构建 Redis key ==========
        String key = CacheConstant.CAPTCHA_IMG + uuid;

        try {
            // ========== 3. 查询 Redis 中的验证码 ==========
            Object cached = redisUtil.get(key);

            // ========== 4. 统一校验：不区分"过期/不存在/错误" ==========
            // cached == null 覆盖：key 不存在 + key 已过期 + key 已被消费（一次性删除）
            // 统一抛 CAPTCHA_ERROR，不单独区分，防止信息泄露
            if (cached == null || !cached.toString().equalsIgnoreCase(code)) {
                throw new BizException(ErrorCode.CAPTCHA_ERROR);
            }

            // 校验通过，继续执行
        } finally {
            // ========== 5. 一次性消费：无论成功失败都删除 ==========
            // 防止攻击者用同一个 uuid 反复撞库（暴力穷举 4 位数字最多 10000 次）
            redisUtil.delete(key);
        }
    }

    /**
     * 邮箱验证码校验
     * @param email 邮箱
     * @param code  用户输入的验证码
     */
    @Override
    public void verifyEmailCode(String email, EmailCodeUsage usage, String code) {
        String key = buildEmailKey(usage, email);
        Object cached = redisUtil.get(key);
        if (cached == null || !cached.toString().equals(code)) {
            // 失败次数 +1，超过阈值直接删掉，强制重新发
            String failKey = buildEmailFailKey(usage, email);
            Long fails = redisUtil.increment(failKey, 1);
            if (fails == 1) {
                redisUtil.expire(failKey, captchaProperties.getEmailTtl(), TimeUnit.SECONDS); // 和验证码同生命周期
            }
            if (fails >= captchaProperties.getMaxVerifyFails()) {
                redisUtil.delete(key);
                redisUtil.delete(failKey);
            }
            throw new BizException(ErrorCode.CAPTCHA_ERROR);
        }
        // 校验通过，删除验证码和失败计数
        redisUtil.delete(key);
        redisUtil.delete(buildEmailFailKey(usage, email));
    }

    /**
     * 构建邮箱验证码 Redis key：captcha:email:{usage}:{email}
     */
    private String buildEmailKey(EmailCodeUsage usage, String email) {
        return CacheConstant.CAPTCHA_EMAIL + usage.getPrefix() + ":" + email;
    }

    /**
     * 构建邮箱验证码校验失败计数 Redis key：captcha:email:fail:{usage}:{email}
     */
    private String buildEmailFailKey(EmailCodeUsage usage, String email) {
        return CacheConstant.CAPTCHA_EMAIL_FAIL + usage.getPrefix() + ":" + email;
    }

    /**
     * 发送邮件
     */
    private void sendMail(String to, String code) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            // 发件人地址：MailProperties 无 from 字段，QQ 邮箱要求 from 与 username 一致，故直接用 username
            helper.setFrom(mailProperties.getUsername());
            helper.setTo(to);
            helper.setSubject(MailConstant.SUBJECT);
            //<b> 是 HTML 的 加粗标签（bold 的缩写）。
            // 正文 = 验证码内容 + 落款 + 免责声明（HTML）
            String content = String.format(MailConstant.CONTENT_TEMPLATE, code, captchaProperties.getEmailTtl() / 60)
                    + MailConstant.SIGNATURE + MailConstant.DISCLAIMER;
            helper.setText(content, true);
            mailSender.send(message);
        } catch (MessagingException e) {
            log.error("发送邮箱验证码失败 to:{}", to, e);
            throw new BizException(ErrorCode.MAIL_SEND_ERROR);
        }
    }
}