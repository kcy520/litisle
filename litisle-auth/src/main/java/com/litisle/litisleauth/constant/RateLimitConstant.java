package com.litisle.litisleauth.constant;

/**
 * 限流常量
 * <p>
 * 集中管理所有 @RateLimiter 注解的参数，避免魔法数字散落在各 Controller 中。
 * 需调整限流阈值时仅需修改此处，无需逐个搜索 Controller。
 *
 * 【设计说明】
 * 1. 注解参数必须是编译期常量（static final），故无法使用 @ConfigurationProperties
 * 2. 按业务模块分组，便于查找和维护
 * 3. 限流窗口时间（WINDOW_SECONDS）全局统一为 10 秒，
 *    差异体现在 count（每窗口允许的最大请求次数）
 */
public final class RateLimitConstant {

    private RateLimitConstant() {
    }

    /** 限流窗口时间（秒），全局统一 */
    public static final long WINDOW_SECONDS = 10L;

    // ========== 认证接口 ==========

    /** 注册 - 每 10 秒最多 10 次/IP */
    public static final long AUTH_REGISTER_MAX = 10L;

    /** 密码登录 - 每 10 秒最多 10 次/IP */
    public static final long AUTH_LOGIN_MAX = 10L;

    /** 邮箱验证码登录 - 每 10 秒最多 10 次/IP */
    public static final long AUTH_EMAIL_LOGIN_MAX = 10L;

    // ========== 验证码接口 ==========

    /** 获取图形验证码 - 每 10 秒最多 10 次/IP */
    public static final long CAPTCHA_IMG_GET_MAX = 10L;

    /** 发送邮箱验证码 - 每 10 秒最多 5 次/IP（防刷，阈值较低） */
    public static final long CAPTCHA_EMAIL_SEND_MAX = 5L;

    /** 校验验证码（图形/邮箱） - 每 10 秒最多 30 次/IP */
    public static final long CAPTCHA_VERIFY_MAX = 30L;
}