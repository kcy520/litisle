package com.litisle.litisleapi.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 注册用户 DTO（auth → user 模块）
 *
 * 【设计说明】
 * 1. 密码由 auth 模块 BCrypt 加密后传入，user 模块直接存储
 * 2. 手机号/邮箱为明文，由 user 模块负责 AES 加密存储（user 模块是用户数据唯一所有者）
 * 3. user 模块负责：用户名/手机号/邮箱唯一性校验 + 分配 READER 角色
 */
@Data
public class RegisterUserDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户名 */
    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 32, message = "用户名长度必须为 3-32 个字符")
    private String username;

    /** 密码（BCrypt 哈希，auth 模块已加密） */
    @NotBlank(message = "密码不能为空")
    private String password;

    /** 手机号（明文，user 模块 AES 加密存储） */
    @NotBlank(message = "手机号不能为空")
    private String phone;

    /** 邮箱（明文，user 模块 AES 加密存储，可选） */
    private String email;

    /** 注册来源（WEB / APP / MINI_PROGRAM） */
    private String source;
}