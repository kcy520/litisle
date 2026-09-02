package com.litisle.litisleuser.domain.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 初次绑定邮箱 DTO
 *
 * 【设计说明】
 * 1. 绑定邮箱属高危敏感操作，需"图形验证码 + 邮箱验证码"双码校验
 * 2. 图形码先做人机识别，邮箱码做归属校验（用途 CHANGE_EMAIL）
 * 3. 邮箱验证码的发送/校验由 auth 模块完成，user 模块仅透传待校验信息
 *
 * @author litisle
 */
@Data
public class BindEmailDTO {

    /** 待绑定邮箱 */
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

    /** 邮箱验证码 */
    @NotBlank(message = "邮箱验证码不能为空")
    private String emailCode;
}
