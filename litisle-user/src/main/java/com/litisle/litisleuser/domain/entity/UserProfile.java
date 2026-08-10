package com.litisle.litisleuser.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.litisle.litisleuser.enums.AvatarColor;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户资料扩展表
 * 删除状态以主表 sys_user.del_flag 为唯一真相源，本表不设 del_flag
 */
@Data
@TableName("sys_user_profile")
public class UserProfile implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户ID（关联 sys_user.id） */
    @TableField("user_id")
    private Long userId;

    /** 笔名（作家专用） */
    @Size(max = 32, message = "笔名长度不能超过 32 个字符")
    @TableField("pen_name")
    private String penName;

    /** 创作方向（作家申请时填写） */
    @Size(max = 100, message = "创作方向长度不能超过 100 个字符")
    @TableField("creation_dir")
    private String creationDir;

    /** 代表作简介 */
    @Size(max = 500, message = "代表作简介长度不能超过 500 个字符")
    @TableField("masterpiece")
    private String masterpiece;

    /** 头像标识：0-无 1-金色（作家）2-白色（纪念作家） */
    @TableField("avatar_color")
    private AvatarColor avatarColor;

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