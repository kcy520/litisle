package com.litisle.litisleuser.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.litisle.litisleuser.enums.UserBanAction;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户封号/解封审计日志表
 * <p>
 * 每次封号/解封（含定时任务自动解封）写入一条流水，保留操作时用户名/角色快照，
 * 供后台追溯与合规审计；不做逻辑删除，保留完整历史。
 */
@Data
@TableName("sys_user_ban_log")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserBanLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID，自增
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 被操作用户ID，关联 sys_user.id
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 被操作用户名（操作时快照）
     * <p>
     * 记录封号/解封那一刻的用户名，即使后续用户改名，审计日志中仍能看到当时的名字
     */
    @TableField("username")
    private String username;

    /**
     * 被操作用户角色快照（逗号分隔）
     * <p>
     * 记录封号/解封那一刻用户拥有的角色编码，如 "ADMIN,OPERATOR"
     * 防止角色变更后无法还原当时的权限状态
     */
    @TableField("role_code")
    private String roleCode;

    /**
     * 操作类型：BAN-封号，UNBAN-解封
     */
    @TableField("action")
    private UserBanAction action;

    /**
     * 封号原因（封号时建议必填，解封时可为空）
     * 前端可以按照现有枚举直接选择，也可以手动输入封号原因
     */
    @TableField("reason")
//    @Size(max = 255, message = "封号原因不能超过255个字符")
    private String reason;

    /**
     * 解封时间（封号时记录，解封时为 null）
     * <p>
     * 表示账号被冻结到哪个时间点，到期后可由定时任务自动解封
     */
    @TableField("frozen_until")
    private LocalDateTime frozenUntil;

    /**
     * 操作者ID
     * <p>
     * 人工操作时为管理员用户ID；定时任务自动解封时为 0，表示系统操作
     */
    @TableField("operator_id")
    private Long operatorId;

    /**
     * 操作者用户名（快照）
     * <p>
     * 记录执行操作的管理员当时的用户名，防止管理员离职/改名后日志无法追溯
     */
    @TableField("operator_name")
    private String operatorName;

    /**
     * 操作时间，即日志记录创建时间
     */
    @TableField("create_time")
    private LocalDateTime createTime;
}