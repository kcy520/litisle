package com.litisle.litisleauth.domain.vo;

import com.litisle.commonsecurity.desensitize.Sensitive;
import com.litisle.commonsecurity.desensitize.SensitiveStrategy;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 当前登录用户信息 VO
 *
 * 【使用场景】GET /auth/me（令牌自检 / 用户信息回显）
 *
 * 【设计说明】
 * 1. 遵循"后端不向前端返回 ID 字段"规范，不返回 userId
 * 2. 敏感字段（手机号、邮箱）由服务端脱敏后返回
 * 3. 密码字段禁止出现在任何 VO 中
 */
@Accessors(chain = true)
@Data
public class LoginUserInfoVO {

    /** 用户名 */
    private String username;

    /** 对外UID（用户间搜索唯一标识） */
    private String uid;

    /** 手机号（脱敏规则：保留前3后4，中间 ****，如 138****8888） */
    @Sensitive(strategy = SensitiveStrategy.PHONE)
    private String phone;

    /** 邮箱（脱敏规则：保留首字符与域名，如 a***@xx.com） */
    @Sensitive(strategy = SensitiveStrategy.EMAIL)
    private String email;

    /** 头像 URL */
    private String avatar;

    /** 头像标识：0-无 1-金色（作家）2-白色（纪念作家） */
    private Integer avatarColor;

    /** 个人简介 */
    private String bio;

    /** 是否 VIP */
    private Boolean vip;

    /** VIP 到期时间（null 表示非 VIP） */
    private LocalDateTime vipExpireTime;

    /** 账号状态：0-禁用 1-正常 2-注销 */
    private Integer status;

    /** 角色编码列表（如 ["READER", "AUTHOR"]） */
    private List<String> roles;

    /** 作家扩展资料（非作家为 null） */
    private AuthorProfile author;

    /**
     * 作家扩展资料（对应 sys_user_profile 表）
     */
    @Data
    public static class AuthorProfile {

        /** 笔名（作家专用） */
        private String penName;

        /** 创作方向 */
        private String creationDir;

        /** 代表作简介 */
        private String masterpiece;
    }
}