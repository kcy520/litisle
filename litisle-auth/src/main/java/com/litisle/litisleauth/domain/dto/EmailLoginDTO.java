package com.litisle.litisleauth.domain.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 邮箱 + 验证码登录请求 DTO
 *
 * 【使用场景】POST /auth/login/sms
 *
 * 【设计说明】
 * 1. 无密码登录，验证码即凭证
 * 2. 验证码由服务端发送至邮箱并缓存（Redis），校验后失效
 */
@Data
public class EmailLoginDTO {

    /** 邮箱（脱敏规则：保留首字符与域名，如 a***@xx.com） */
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 64, message = "邮箱长度不能超过 64 个字符")
    private String email;

    /** 邮箱验证码（4-6 位数字） */
    @NotBlank(message = "验证码不能为空")
    @Size(min = 4, max = 6, message = "验证码长度 4-6 位")
    private String code;

    /** 是否记住我（true=延长令牌有效期） */
    private Boolean rememberMe = false;
}