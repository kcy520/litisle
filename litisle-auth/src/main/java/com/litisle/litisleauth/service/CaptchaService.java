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
     *
     * 你的担忧点在于"图形验证码能否被跨接口复用/串用"。从代码看，这个疑虑可以被当前设计自然化解，原因如下：
     *             1. 图形验证码本身就不带业务用途（这是正确设计）
     *             图形验证码的职责是"人机识别"（证明操作者是真人），它本质是通用能力，与"换绑邮箱 / 登录 / 注册"哪个场景无关。
     *             2. 真正的场景隔离由"邮箱验证码"的 usage 承担(比如邮箱验证码发送前的人机校验)
     *             3. 为什么跨接口传图形码"校验通过"也安全？
     *             当前系统对"换绑邮箱"的授权链 = 真人（图形码，通用） + 拥有目标邮箱（CHANGE_EMAIL 码，专用）。双码各自职责不同，你的担忧被邮箱码的 usage 隔离硬性兜底了。
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