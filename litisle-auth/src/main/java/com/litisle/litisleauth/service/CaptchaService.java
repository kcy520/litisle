package com.litisle.litisleauth.service;

import com.litisle.litisleauth.domain.vo.CaptchaImgVO;
import com.litisle.litisleauth.enums.EmailCodeUsage;

/**
 * 验证码服务
 *
 * 【职责】
 * 1. 图形验证码：生成（captcha:img:{uuid}）/ 校验（一次性消费）
 * 2. 邮箱验证码：发送/校验（captcha:email:{usage}:{email}）+ 防刷（captcha:email:limit:{ip}）
 */
public interface CaptchaService {

    /**
     * 生成图形验证码（存储 Redis 并返回 uuid + base64 图片）
     *
     * @return 图形验证码 VO
     */
    CaptchaImgVO generateImgCode();

    /**
     * 发送邮箱验证码
     *
     * @param email 目标邮箱
     * @param usage 验证码用途（区分场景，防止跨场景复用）
     * @param ip    客户端 IP（防刷）
     */
    void sendEmailCode(String email, EmailCodeUsage usage, String ip);

    /**
     * 校验图形验证码（校验通过后立即删除，一次性）
     *
     * @param uuid 图形验证码标识
     * @param code 用户输入的验证码
     */
    void verifyImgCode(String uuid, String code);

    /**
     * 校验邮箱验证码（校验通过后立即删除，一次性）
     *
     * @param email 邮箱
     * @param usage 验证码用途
     * @param code  用户输入的验证码
     */
    void verifyEmailCode(String email, EmailCodeUsage usage, String code);
}