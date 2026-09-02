package com.litisle.litisleapi.dto.captcha;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 改绑邮箱双码校验 DTO（user 模块 → auth 模块 Feign 调用）
 *
 * 【设计说明】
 * 1. 一次校验"图形验证码 + 邮箱验证码（用途 CHANGE_EMAIL）"，双码防爆破
 * 2. 邮箱验证码发送/校验均由 auth 模块负责（user 模块无验证码服务，跨模块走 Feign）
 * 3. 不含业务敏感数据，仅透传待校验的验证码信息
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class ChangeEmailVerifyDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 图形验证码标识（Redis key = captcha:img:{uuid}） */
    @NotBlank(message = "图形验证码标识不能为空")
    private String imgUuid;

    /** 图形验证码（用户输入，一次性） */
    @NotBlank(message = "图形验证码不能为空")
    private String imgCode;

    /** 目标（新绑定/更换）邮箱 */
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 64, message = "邮箱长度不能超过64个字符")
    private String email;

    /** 邮箱验证码（一次性，用途 CHANGE_EMAIL） */
    @NotBlank(message = "邮箱验证码不能为空")
    private String emailCode;
}
