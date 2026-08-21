package com.litisle.litisleauth.domain.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

/**
 * 用户名密码注册请求 DTO
 *
 * 【使用场景】POST /auth/register
 *
 * 【设计说明】
 * 1. 仅包含注册所需的最小字段集
 * 2. 用户名 / 密码 / 手机号必填，邮箱可选
 * 3. 验证码用于防刷与身份校验
 */
@Data
public class RegisterRequestDTO {

    /** 用户名（3-32 位，仅字母、数字、下划线） */
    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 32, message = "用户名长度必须为 3-32 个字符")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名仅支持字母、数字、下划线")
    private String username;

    /** 密码（8-32 位，服务端 BCrypt 加密存储） */
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 32, message = "密码长度必须为 8-32 位")
    private String password;

    /** 手机号（脱敏规则：保留前3后4，中间 ****，如 138****8888） */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /** 邮箱（可选，脱敏规则：保留首字符与域名，如 a***@xx.com） */
    @Email(message = "邮箱格式不正确")
    @Size(max = 64, message = "邮箱长度不能超过 64 个字符")
    private String email;

    /** 图形验证码标识（Redis key = captcha:img:{uuid}） */
    @NotBlank(message = "图形验证码标识不能为空")
    private String uuid;

    /** 验证码（图形码 ，防刷校验） */
    @NotBlank(message = "验证码不能为空")
    @Size(min = 4, max = 6, message = "验证码长度 4-6 位")
    private String imgCode;

    /** 邮箱验证码（图形码 / 邮箱验证码，防刷校验） */
    @NotBlank(message = "验证码不能为空")
    @Size(min = 4, max = 6, message = "验证码长度 4-6 位")
    private String emailCode;

    /** 注册来源（WEB / APP / MINI_PROGRAM），默认 WEB */
    private String source = "WEB";
}