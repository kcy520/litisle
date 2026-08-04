package com.litisle.litisleuser.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 第三方账号绑定表
 * 物理删除，解绑时直接 DELETE，由 @AuditLog 切面记录审计日志
 */
@Data
@TableName("sys_oauth_binding")
public class SysOAuthBinding implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户ID */
    @NotNull(message = "用户ID不能为空")
    @TableField("user_id")
    private Long userId;

    /** OAuth 提供方：WECHAT / QQ */
    @NotBlank(message = "OAuth 提供方不能为空")
    @Size(max = 16, message = "OAuth 提供方长度不能超过 16 个字符")
    @TableField("provider")
    private String provider;

    /** 应用ID（预留多应用扩展，同一 provider 下区分不同 app） */
    @Size(max = 32, message = "应用ID长度不能超过 32 个字符")
    @TableField("app_id")
    private String appId;

    /** 第三方 openId */
    @NotBlank(message = "openId 不能为空")
    @Size(max = 64, message = "openId 长度不能超过 64 个字符")
    @TableField("open_id")
    private String openId;

    /** 第三方 unionId（可选） */
    @Size(max = 64, message = "unionId 长度不能超过 64 个字符")
    @TableField("union_id")
    private String unionId;

    /** 绑定时间 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}