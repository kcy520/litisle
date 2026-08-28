package com.litisle.litisleauth.domain.dto;

import com.litisle.litisleauth.enums.EmailCodeUsage;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 校验邮箱验证码请求 DTO
 *
 * 【使用场景】POST /auth/captcha/email/verify
 */
@Data
public class VerifyEmailCodeDTO {

    /** 邮箱 */
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

//    /** 验证码用途 */
//    @NotNull(message = "验证码用途不能为空")
//    private EmailCodeUsage usage;

    /** 用户输入的验证码 */
    @NotBlank(message = "验证码不能为空")
    @Size(min = 4, max = 6, message = "验证码长度 4-6 位")
    private String code;
}
