package com.litisle.litisleuser.domain.vo;

import com.litisle.litisleuser.enums.AvatarColor;

import java.time.LocalDateTime;

/**
 * 用户个人资料回显 VO
 *
 * 使用场景：
 * - 个人资料编辑页回显
 * - 资料修改接口（PUT /profile）返回
 *
 * 设计原则：
 * 1. 仅包含“用户可主动编辑 & 对外展示”的字段
 * 2. 不含账号安全/状态类字段（phone/email/status/frozen 在 UserSelfVO）
 * 3. 永远返回完整视图，非 diff
 */
public class UserUpdateProfileVO {

    /** 用户名 */
    private String username;

    /** 个人简介 */
    private String bio;

    /** 头像访问地址（已拼接 CDN/域名） */
    private String avatar;

    /** 头像配色：0-无 1-金色 2-白色 */
    private AvatarColor avatarColor;

    /** 笔名（作家） */
    private String penName;

    /** 创作方向 */
    private String creationDir;

    /** 代表作简介 */
    private String masterpiece;

    /** 是否拥有有效 VIP（vip_expire_time >= now） */
    private Boolean vip;

    /** VIP 到期时间（非 VIP 为 null） */
    private LocalDateTime vipExpireTime;
}