package com.litisle.litisleapi.dto.user;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 认证用户聚合 DTO（auth ← user 模块）
 *
 * 【设计说明】
 * 1. 由 user 模块聚合返回（用户 + 角色 + 权限 + 作家扩展资料），auth 不直接查角色表
 * 2. 密码为 BCrypt 哈希，仅用于 auth 模块校验密码
 * 3. 手机号/邮箱为明文（user 模块已解密），auth 模块展示时自行脱敏
 */
@Accessors(chain = true)
@Data
public class AuthUserDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户ID */
    private Long userId;

    /** 用户名 */
    private String username;

    /** 密码（BCrypt 哈希，仅登录校验用） */
    private String password;

    /** 手机号（明文，展示时脱敏） */
    private String phone;

    /** 邮箱（明文，展示时脱敏） */
    private String email;

    /** 头像 URL */
    private String avatar;

    /** 头像标识：0-无 1-金色（作家）2-白色（纪念作家） */
    private Integer avatarColor;

    /** 个人简介 */
    private String bio;

    /** 是否 VIP */
    private Boolean vip;

    /** VIP 到期时间 */
    private LocalDateTime vipExpireTime;

    /** 账号状态：0-禁用 1-正常 2-注销 */
    private Integer status;

    /** 封禁截止时间（null=未封禁） */
    private LocalDateTime frozenUntil;

    /** 注销时间（null=未注销） */
    private LocalDateTime cancelTime;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 角色编码列表（如 ["READER", "AUTHOR"]） */
    private List<String> roles;

    /** 权限编码列表（如 ["user:read"]） */
    private List<String> permissions;

    /** 笔名（作家专用） */
    private String penName;

    /** 创作方向（作家专用） */
    private String creationDir;

    /** 代表作简介（作家专用） */
    private String masterpiece;
}