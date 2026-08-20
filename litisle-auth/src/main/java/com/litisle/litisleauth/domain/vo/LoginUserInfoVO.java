package com.litisle.litisleauth.domain.vo;

import com.litisle.commonsecurity.desensitize.Sensitive;
import com.litisle.commonsecurity.desensitize.SensitiveStrategy;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 当前登录用户信息（/auth/me 返回）
 */
@Data
public class LoginUserInfoVO {

//    /** 用户ID */
//    private Long userId;

    /** 用户名 */
    private String username;

    /**
     * 手机号（已脱敏）
     * 示例：138****8888
     */
    private String phone;

    /**
     * 邮箱（已脱敏）
     * 示例：a***@xx.com
     */
    @Sensitive(strategy = SensitiveStrategy.EMAIL)
    private String email;

    /** 头像地址 */
    private String avatar;

    /**
     * 头像标识
     * 0-无 1-金色（作家） 2-白色（纪念作家）
     */
    private Integer avatarColor;

    /** 个人简介 */
    private String bio;

    /** 是否 VIP */
    private Boolean vip;

    /** VIP 到期时间（null 表示非 VIP） */
    private LocalDateTime vipExpireTime;

    /**
     * 账号状态
     * 0-禁用 1-正常 2-注销
     */
    private Integer status;

    /** 角色编码列表（如 ["READER", "AUTHOR"]） */
    private List<String> roles;

//    /** 权限编码列表（如 ["article:read", "comment:write"]） */
//    private List<String> permissions;

    /**
     * 作家扩展资料（非作家为 null）
     */
    @Data
    public static class AuthorProfile {

        /** 笔名 */
        private String penName;

        /** 创作方向 */
        private String creationDir;

        /** 代表作简介 */
        private String masterpiece;
    }

    private AuthorProfile author;
}