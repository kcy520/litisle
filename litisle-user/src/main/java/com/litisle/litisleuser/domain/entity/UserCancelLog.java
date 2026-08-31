package com.litisle.litisleuser.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.litisle.litisleuser.enums.UserCancelSource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户注销审计日志表
 * <p>
 * 记录用户自助注销与管理员强制注销两条流水，保留注销时用户名/角色/来源/原因快照，
 * 供后台追溯与合规审计（注销后原用户名可能被复用，需以快照留存凭证）。
 */
@Data
@TableName("sys_user_cancel_log")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserCancelLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID，自增
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 被注销用户ID，关联 sys_user.id
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 被注销用户名（操作时快照）
     * <p>
     * 记录注销那一刻的用户名，即使注销后用户名被复用/改名，日志仍保留当时快照
     */
    @TableField("username")
    private String username;

    /**
     * 被注销用户角色快照（逗号分隔）
     * <p>
     * 记录注销那一刻用户拥有的角色编码，如 "AUTHOR,OPERATOR"
     */
    @TableField("role_code")
    private String roleCode;

    /**
     * 注销来源：SELF-用户自助注销，ADMIN-管理员强制注销
     */
    @TableField("source")
    private UserCancelSource source;

    /**
     * 注销原因（管理员强制注销时必填；自助注销可为空）
     * <p>
     * 前端可参照常用原因选择，也可自定义描述
     */
    @TableField("reason")
    private String reason;

    /**
     * 操作者ID（自助注销为本人；管理员注销为操作管理员ID）
     */
    @TableField("operator_id")
    private Long operatorId;

    /**
     * 操作者用户名（快照）
     * <p>
     * 记录执行注销的管理员用户名，防止管理员离职/改名后无法追溯
     */
    @TableField("operator_name")
    private String operatorName;

    /**
     * 注销操作时间，即日志记录创建时间
     */
    @TableField("create_time")
    private LocalDateTime createTime;
}
