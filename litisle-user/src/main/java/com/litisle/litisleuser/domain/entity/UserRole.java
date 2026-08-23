package com.litisle.litisleuser.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 用户-角色关联表
 * 主键为自增 id，业务唯一性由 uk_user_role(user_id, role_id) 保证。
 */
@Data
@TableName("sys_user_role")
public class UserRole implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户ID */
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    /** 角色ID */
    @NotNull(message = "角色ID不能为空")
    private Long roleId;
}