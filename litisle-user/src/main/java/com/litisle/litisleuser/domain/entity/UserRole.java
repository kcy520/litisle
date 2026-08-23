package com.litisle.litisleuser.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 用户-角色关联表
 *
 * 【说明】该表为复合主键（user_id + role_id），MyBatis-Plus 的 BaseMapper 需要单一 @TableId。
 * 此处以 userId 作为逻辑主键（IdType.INPUT）以满足启动注入要求。
 * 若需严格区分，建议为表增加自增 id 列，并将本字段改回普通字段。
 */
@Data
@TableName("sys_user_role")
public class UserRole implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户ID */
    @TableId(type = IdType.INPUT)
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    /** 角色ID */
    @NotNull(message = "角色ID不能为空")
    private Long roleId;
}