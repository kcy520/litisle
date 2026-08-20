package com.litisle.litisleauth.domain.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class PasswordLoginDTO {

    @NotBlank(message = "账号不能为空")
    private String account; // 用户名/手机/邮箱

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 32)
    private String password;

    private Boolean rememberMe = false;
}