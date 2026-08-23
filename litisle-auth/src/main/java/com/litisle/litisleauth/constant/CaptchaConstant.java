package com.litisle.litisleauth.constant;

/**
 * 验证码常量
 *
 * 【设计说明】
 * 1. 图形验证码的尺寸、字符数、干扰线数等纯技术参数
 * 2. TTL / 防刷阈值等运营参数在 CaptchaProperties（yml 配置）
 */
public final class CaptchaConstant {

    private CaptchaConstant() {
    }

    /** 图形验证码宽度（px） */
    public static final int IMG_WIDTH = 120;

    /** 图形验证码高度（px） */
    public static final int IMG_HEIGHT = 40;

    /** 图形验证码字符个数 */
    public static final int IMG_CODE_COUNT = 4;

    /** 图形验证码干扰线数量 */
    public static final int IMG_LINE_COUNT = 5;

    /** 邮箱验证码长度（纯数字） */
    public static final int EMAIL_CODE_LENGTH = 6;
}
