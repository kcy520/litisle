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
 * 2. 密码为 BCrypt 哈希，仅用于 auth 模块校验密码，禁止外传
 * 3. 手机号/邮箱为【明文】（user 端解密后内网传输），仅限服务间调用；
 *    对前端响应由 VO 字段上的 @Sensitive 注解在 Jackson 序列化时自动脱敏，
 *    禁止把本 DTO 原样透传给前端（密文/明文均不可出网关）
 */
@Accessors(chain = true)
@Data
public class AuthUserDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户ID */
    private Long userId;

    /** 对外UID（用户间搜索唯一标识，随机生成，永久不变） */
    private String uid;

    /** 用户名 */
    private String username;

    /** 密码（BCrypt 哈希，仅登录校验用） */
    private String password;

    /** 手机号（明文，仅限内网 Feign；对前端响应由 VO @Sensitive(PHONE) 自动脱敏） */
    private String phone;

    /** 邮箱（明文，仅限内网 Feign；对前端响应由 VO @Sensitive(EMAIL) 自动脱敏） */
    private String email;

    /** 头像文件ID（SeaweedFS fileId，URL 由调用方拼接） */
    private String avatarFileId;

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