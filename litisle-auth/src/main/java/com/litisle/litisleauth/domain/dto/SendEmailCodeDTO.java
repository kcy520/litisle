package com.litisle.litisleauth.domain.dto;

import com.litisle.litisleauth.enums.EmailCodeUsage;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发送邮箱验证码请求 DTO
 *
 * 【使用场景】POST /auth/captcha/email
 */
@Data
public class SendEmailCodeDTO {

    /** 目标邮箱 */
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 64, message = "邮箱长度不能超过 64 个字符")
    private String email;

    /** 验证码用途（REGISTER / LOGIN / CHANGE_EMAIL / RESET_PASSWORD / CANCEL_ACCOUNT） */
    @NotNull(message = "验证码用途不能为空")
    private EmailCodeUsage usage;
}
