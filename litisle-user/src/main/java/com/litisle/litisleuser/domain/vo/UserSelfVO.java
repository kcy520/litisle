package com.litisle.litisleuser.domain.vo;

import com.litisle.commonsecurity.desensitize.Sensitive;
import com.litisle.commonsecurity.desensitize.SensitiveStrategy;
import com.litisle.litisleuser.enums.AvatarColor;
import com.litisle.commoncore.enums.UserStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 当前登录用户 VO（我的资料）
 *
 * 【使用场景】
 * 1. 用户中心 /me 接口
 * 2. 个人资料编辑页回显
 * 3. 仅对当前登录用户可见
 *
 * 【设计说明】
 * 1. 包含账号信息 + 扩展资料信息
 * 2. 敏感字段（手机号、邮箱）需脱敏后返回
 * 3. VIP、封禁等状态字段在此返回
 * 4. 密码字段禁止出现在任何 VO 中
 *
 * @author litisle
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserSelfVO {

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 用户名
     */
    private String username;

    /**
     * 邮箱（脱敏返回，如 ex***@xx.com）
     */
    @Sensitive(strategy = SensitiveStrategy.EMAIL)
    private String email;

    /**
     * 手机号（脱敏返回，如 138****8888）
     */
    @Sensitive(strategy = SensitiveStrategy.PHONE)
    private String phone;

    /**
     * 头像 URL（已拼接完整访问地址）
     */
    private String avatar;

    /**
     * 个人简介
     */
    private String bio;

    /**
     * 是否 VIP
     */
    private Boolean vip;

    /**
     * VIP 到期时间（非 VIP 可为 null）
     */
    private LocalDateTime vipExpireTime;

    /**
     * 账号状态：禁用/正常/注销（JSON 输出 desc，如"正常"）
     */
    private UserStatus status;

    /**
     * 是否被封禁（true=封禁中）
     */
    private Boolean frozen;

//    private Boolean enabled ;

    //status = 身份合法性
    //frozen = 行为可用性

    /**
     * 封禁截止时间（未封禁为 null）
     */
    private LocalDateTime frozenUntil;

    /**
     * 注销时间（未注销为 null）
     */
    private LocalDateTime cancelTime;

    /**
     * 笔名（作家专用）
     */
    private String penName;

    /**
     * 创作方向（作家申请时填写）
     */
    private String creationDir;

    /**
     * 代表作简介
     */
    private String masterpiece;

    /**
     * 头像标识：无/金色（作家）/白色（纪念作家）（JSON 输出 desc，如"金色"）
     */
    private AvatarColor avatarColor;

    /**
     * 创建时间（注册时间）
     */
    private LocalDateTime createTime;
}