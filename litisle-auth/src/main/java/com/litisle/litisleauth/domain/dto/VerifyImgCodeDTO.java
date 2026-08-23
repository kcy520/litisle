package com.litisle.litisleauth.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 校验图形验证码请求 DTO
 *
 * 【使用场景】POST /auth/captcha/img/verify
 */
@Data
public class VerifyImgCodeDTO {

    /** 图形验证码标识（Redis key = captcha:img:{uuid}） */
    @NotBlank(message = "图形验证码标识不能为空")
    private String uuid;

    /** 用户输入的验证码 */
    @NotBlank(message = "验证码不能为空")
    @Size(min = 4, max = 6, message = "验证码长度 4-6 位")
    private String code;
}
