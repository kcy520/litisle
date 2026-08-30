package com.litisle.litisleuser.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 后台用户管理 - 详情 VO
 * <p>
 * 聚合用户基本信息、角色编码、VIP 状态与作者扩展资料；手机号/邮箱为密文存库不做明文回显。
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class UserAdminDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    // ========== 基本信息 ==========
    /** 用户ID */
    private Long id;

    /** 用户名 */
    private String username;

    /** 头像URL */
    private String avatar;

    /** 头像标识（0-无 1-金 2-白） */
    private Integer avatarColor;

    /** 个人简介 */
    private String bio;

    /** 状态：0-禁用 1-正常 2-注销 */
    private Integer status;

    /** 角色编码列表 */
    private List<String> roleCodes;

    // ========== VIP 状态 ==========
    /** 是否 VIP（有效期内） */
    private Boolean vip;

    /** VIP 到期时间 */
    private LocalDateTime vipExpireTime;

    // ========== 账号状态 ==========
    /** 封禁截止时间（null=未封禁） */
    private LocalDateTime frozenUntil;

    /** 注销时间（null=未注销） */
    private LocalDateTime cancelTime;

    /** 创建时间 */
    private LocalDateTime createTime;

    // ========== 作者扩展资料 ==========
    /** 笔名（作家专用） */
    private String penName;

    /** 创作方向 */
    private String creationDir;

    /** 代表作简介 */
    private String masterpiece;
}
