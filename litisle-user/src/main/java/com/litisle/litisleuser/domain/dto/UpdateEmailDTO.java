package com.litisle.litisleuser.domain.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更换已绑定邮箱 DTO
 *
 * 【设计说明】
 * 1. 更换邮箱属高危敏感操作，需"图形验证码 + 邮箱验证码"双码校验
 * 2. 结构同 BindEmailDTO，独立成类以便与"初次绑定"区分业务场景、独立扩展字段
 * 3. 邮箱验证码发送/校验由 auth 模块负责（用途 CHANGE_EMAIL），user 模块仅透传校验信息
 *
 * @author litisle
 */
@Data
public class UpdateEmailDTO {

    /** 新邮箱（更换后目标邮箱） */
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 64, message = "邮箱长度不能超过64个字符")
    private String newEmail;

    /** 图形验证码标识 */
    @NotBlank(message = "图形验证码不能为空")
    private String imgUuid;

    /** 图形验证码 */
    @NotBlank(message = "图形验证码不能为空")
    private String imgCode;

    /** 新邮箱验证码（用途 CHANGE_EMAIL） */
    @NotBlank(message = "邮箱验证码不能为空")
    private String emailCode;
}
