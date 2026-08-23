package com.litisle.litisleauth.utils;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.CircleCaptcha;
import com.litisle.litisleauth.constant.CaptchaConstant;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;

/**
 * 图形验证码生成工具类
 *
 * 【设计说明】
 * 1. 纯技术操作：只负责"生成验证码图片 + 验证码文本"，不涉及存储与业务
 * 2. 无状态、可复用：注册/登录等接口需要图形验证码时均可调用
 * 3. 存储、校验、防刷等业务逻辑由 CaptchaService 负责
 */
@Component
public class CaptchaCodeUtil {

    /**
     * 生成图形验证码
     *
     * @return 验证码文本 + base64 图片
     */
    public CaptchaCode generate() {

        CircleCaptcha captcha = CaptchaUtil.createCircleCaptcha(
                CaptchaConstant.IMG_WIDTH,      // 图片宽度（px），如 120
                CaptchaConstant.IMG_HEIGHT,     // 图片高度（px），如 40
                CaptchaConstant.IMG_CODE_COUNT, // 验证码字符个数，如 4（生成 4 位字母/数字）
                CaptchaConstant.IMG_LINE_COUNT  // 干扰线数量，如 20（线条越多越难识别，防 OCR）
        );
        return new CaptchaCode(captcha.getCode(), captcha.getImageBase64Data());
    }

    /**
     * 验证码生成结果（内部载体，不对外暴露）
     */
    @Data
    @AllArgsConstructor
    public static class CaptchaCode {

        /** 验证码文本（仅服务端可见，用于校验） */
        private String code;

        /** 验证码 base64 图片（返回前端展示） */
        private String base64;
    }
}
