package com.litisle.litisleuser.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.litisle.litisleuser.enums.AvatarColor;
import com.litisle.commoncore.enums.DelFlag;
import com.litisle.commoncore.enums.UserStatus;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户表
 */
@Data
@Accessors(chain = true)
@TableName("sys_user")
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户名 */
    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 32, message = "用户名长度必须在 3~32 个字符之间")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名只能包含字母、数字和下划线")
    @TableField("username")
    private String username;

    /** 对外UID（用户间搜索唯一标识，随机生成，永久不变） */
    @Size(max = 16, message = "UID长度不能超过 16 个字符")
    @TableField("uid")
    private String uid;

    /** 密码（BCrypt 哈希） */
    @NotBlank(message = "密码不能为空")
    @Size(min = 3, max = 32, message = "密码长度 3-32 位")
    @TableField("password")
    private String password;

    /** 手机号 */
    @Size(max = 20, message = "手机号长度不能超过 20 个字符")
//    @Pattern(regexp = "^1[3-9]\\d{9}$|^$", message = "手机号格式不正确")
//    @NotBlank(message = "手机号不能为空")
    @Pattern(
            regexp = "^1[3-9]\\d{9}$",
            message = "请输入有效的手机号"
    )
    @TableField("phone")
    private String phone;

    /** 邮箱 */
    @Email(message = "邮箱格式不正确")
    @Size(max = 64, message = "邮箱长度不能超过 64 个字符")
    @TableField("email")
    private String email;

    /** 手机号 HMAC-SHA256 摘要（确定性，唯一索引 uk_phone_hash_del 与查重走本列；密钥见 CryptoAutoConfiguration） */
    @Size(max = 64, message = "手机号摘要长度不能超过 64 个字符")
    @TableField("phone_hash")
    private String phoneHash;

    /** 邮箱 HMAC-SHA256 摘要（确定性，唯一索引 uk_email_hash_del 与查重走本列；未绑定邮箱时为 null，MySQL 唯一索引不约束 NULL） */
    @Size(max = 64, message = "邮箱摘要长度不能超过 64 个字符")
    @TableField("email_hash")
    private String emailHash;

    /** 头像文件ID（SeaweedFS fileId，URL 运行时通过 FileService 反查拼接；默认头像走代码常量兜底） */
    @Size(max = 64, message = "头像文件ID长度不能超过 64 个字符")
    @TableField("avatar_file_id")
    private String avatarFileId;

    /** 头像标识：0-无 1-金色（作家）2-白色（纪念作家） */
    @TableField("avatar_color")
    private AvatarColor avatarColor;

    /** 个人简介 */
    @Size(max = 200, message = "个人简介长度不能超过 200 个字符")
    @TableField("bio")
    private String bio;

    /** VIP 到期时间 */
    @TableField("vip_expire_time")
    private LocalDateTime vipExpireTime;

    /** 封禁截止时间（null=未封禁） */
    @TableField("frozen_until")
    private LocalDateTime frozenUntil;

    /** 注销时间（用于手机号冷却期计算，null=未注销过） */
    @TableField("cancel_time")
    private LocalDateTime cancelTime;

    /** 状态：0-禁用 1-正常 2-注销 */
    @TableField("status")
    private UserStatus status;

    /** 删除标志：0-未删除 1-已删除 */
    @TableField("del_flag")
    private DelFlag delFlag;

    /** 乐观锁版本号 */
    @Version
    @TableField("version")
    private Integer version;

    /** 创建时间 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}