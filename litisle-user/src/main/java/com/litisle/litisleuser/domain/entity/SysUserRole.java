package com.litisle.litisleuser.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 用户-角色关联表
 */
@Data
@TableName("sys_user_role")
public class SysUserRole implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户ID */
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    /** 角色ID */
    @NotNull(message = "角色ID不能为空")
    private Long roleId;
}