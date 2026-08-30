package com.litisle.litisleuser.domain.dto;

import com.litisle.commoncore.enums.UserStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 后台用户管理 - 状态变更 DTO
 * <p>
 * 支持启用 / 禁用（封号）。封号时解封时间必填，缺省默认 7 日；
 * 截止时间会被向上取整到下一个每日 03:00，使"解封校验"统一落在每天凌晨 3 点这一固定粒度上，
 * 便于后台定时任务按点到点扫描，提升效率。
 *
 * @author litisle
 */
@Data
public class UserStatusChangeDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 目标状态：1-正常(启用) 0-禁用(封号)，不允许传注销 */
    @NotNull(message = "目标状态不能为空")
    private UserStatus status;

    /** 封禁截止时间（封号时必填，缺省默认 7 日；向上取整到下一个每日 03:00） */
    private LocalDateTime frozenUntil;

    /** 封号原因（封号时必填，用于审计日志；解封时可为空） */
    @Size(max = 255, message = "封号原因不能超过255个字符")
    private String reason;
}
