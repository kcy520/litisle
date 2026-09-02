package com.litisle.litisleuser.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 后台用户管理 - 列表行 VO
 * <p>
 * 手机号/邮箱为密文存库，列表不做明文回显，故不暴露敏感字段。
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class UserAdminVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户ID */
    private Long id;

    /** 用户名 */
    private String username;

    /** 对外UID（用户间搜索唯一标识） */
    private String uid;

    /** 头像URL */
    private String avatar;

    /** 头像标识（0-无 1-金 2-白） */
    private Integer avatarColor;

    /** 个人简介 */
    private String bio;

    /** 状态：0-禁用 1-正常 2-注销 */
    private Integer status;

    /** 是否 VIP（预计时间内有效） */
    private Boolean vipValid;

    /** 角色编码列表 */
    private List<String> roleCodes;
}
