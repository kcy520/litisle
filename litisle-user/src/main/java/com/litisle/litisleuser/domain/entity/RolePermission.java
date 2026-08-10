package com.litisle.litisleuser.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;

/**
 * 角色-权限关联表
 */
@Data
@TableName("sys_role_permission")
//@KeySequence("sys_role_permission_seq") // 如你使用 Oracle/PostgreSQL 可保留，MySQL 可删除
public class RolePermission implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 角色ID */
    @TableId(value = "role_id", type = IdType.INPUT)
    private Long roleId;

    /** 权限ID */
    @TableField("permission_id")
    private Long permissionId;
}