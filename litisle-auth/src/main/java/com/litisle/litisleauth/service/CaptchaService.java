package com.litisle.litisleauth.service;

/**
 * 验证码服务
 *
 * 【职责】
 * 1. 图形验证码：校验（captcha:img:{uuid}）
 * 2. 邮箱验证码：发送/校验（captcha:email:{email}）+ 防刷（captcha:email:limit:{ip}）
 */
public interface CaptchaService {

    /**
     * 发送邮箱验证码
     *
     * @param email 目标邮箱
     * @param ip    客户端 IP（防刷）
     */
    void sendEmailCode(String email, String ip);

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
     * @param code  用户输入的验证码
     */
    void verifyEmailCode(String email, String code);
}