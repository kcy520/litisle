package com.litisle.litisleauth.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图形验证码响应 VO
 *
 * 【使用场景】GET /auth/captcha/img
 *
 * 【设计说明】
 * 1. 不返回验证码文本（仅服务端可见），防止前端/抓包直接读取
 * 2. uuid 用于后续校验时定位 Redis 中的验证码
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CaptchaImgVO {

    /** 图形验证码标识（Redis key = captcha:img:{uuid}） */
    private String uuid;

    /** 图形验证码 base64 图片（前端直接用于 <img src="data:image/png;base64,...">） */
    private String imgBase64;
}
