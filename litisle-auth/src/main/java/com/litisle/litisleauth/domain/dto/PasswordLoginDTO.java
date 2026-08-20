package com.litisle.litisleauth.domain.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用户名密码登录请求 DTO
 *
 * 【使用场景】POST /auth/login
 *
 * 【设计说明】
 * 1. account 兼容用户名 / 手机号 / 邮箱三种登录方式
 * 2. rememberMe 决定令牌有效期（记住我=长期，否则=短期）
 */
@Data
public class PasswordLoginDTO {

    /** 登录账号（用户名 / 手机号 / 邮箱） */
    @NotBlank(message = "账号不能为空")
    @Size(max = 64, message = "账号长度不能超过 64 个字符")
    private String account;

    /** 登录密码 */
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 32, message = "密码长度必须为 8-32 位")
    private String password;

    /** 是否记住我（true=延长令牌有效期） */
    private Boolean rememberMe = false;
}