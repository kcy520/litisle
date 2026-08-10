package com.litisle.litisleuser.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.litisle.litisleuser.enums.PermStatusEnum;
import com.litisle.litisleuser.enums.PermTypeEnum;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 权限表（菜单与按钮/API 统一管理，parent_id 自关联形成树形结构）
 */
@Data
@TableName("sys_permission")
public class Permission implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 权限ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 父权限ID（0=根节点） */
    @NotNull(message = "父权限ID不能为空")
    @TableField("parent_id")
    private Long parentId;

    /** 权限编码（如 user:read / article:write） */
    @NotBlank(message = "权限编码不能为空")
    @Size(max = 64, message = "权限编码长度不能超过 64 个字符")
    @Pattern(regexp = "^[a-zA-Z0-9_:]+$", message = "权限编码格式非法")
    @TableField("perm_code")
    private String permCode;

    /** 权限名称 */
    @NotBlank(message = "权限名称不能为空")
    @Size(max = 32, message = "权限名称长度不能超过 32 个字符")
    @TableField("perm_name")
    private String permName;

    /** 类型：1-菜单 2-按钮/API */
    @NotNull(message = "权限类型不能为空")
    @TableField("perm_type")
    private PermTypeEnum permType;

    /** 前端路由路径（菜单类型用） */
    @Size(max = 128, message = "路由路径长度不能超过 128 个字符")
    @TableField("path")
    private String path;

    /** 前端组件路径（菜单类型用） */
    @Size(max = 128, message = "组件路径长度不能超过 128 个字符")
    @TableField("component")
    private String component;

    /** 图标 */
    @Size(max = 64, message = "图标长度不能超过 64 个字符")
    @TableField("icon")
    private String icon;

    /** 排序（同级内升序） */
    @TableField("sort")
    private Integer sort;

    /** 状态：0-禁用 1-正常 */
    @TableField("status")
    private PermStatusEnum status;

    /** 创建时间 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}