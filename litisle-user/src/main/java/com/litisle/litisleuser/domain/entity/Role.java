package com.litisle.litisleuser.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 角色表
 */
@Data
@TableName("sys_role")
public class Role implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 角色ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 角色编码 */
    @NotBlank(message = "角色编码不能为空")
    @Size(max = 32, message = "角色编码长度不能超过 32 个字符")
    @TableField("role_code")
    private String roleCode;

    /** 角色名称 */
    @NotBlank(message = "角色名称不能为空")
    @Size(max = 32, message = "角色名称长度不能超过 32 个字符")
    @TableField("role_name")
    private String roleName;

    /** 角色类型：1-系统内置 2-业务角色 */
    @NotNull(message = "角色类型不能为空")
    @TableField("role_type")
    private Integer roleType;

    /** 创建时间 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}