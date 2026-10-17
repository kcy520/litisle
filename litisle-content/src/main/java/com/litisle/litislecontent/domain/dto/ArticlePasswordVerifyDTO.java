package com.litisle.litislecontent.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 密码访问校验入参（[4.2] POST /api/content/article/{id}/verify-password）
 *
 * <p>content 模块本地 DTO（非跨服务契约，不放 litisle-api）：密码字段仅在
 * verify-password 端点出入参，契约库无需感知</p>
 *
 * @author litisle
 */
@Data
public class ArticlePasswordVerifyDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户输入的访问密码（明文传输走 HTTPS；服务端 BCrypt 比对，不回显、不落日志） */
    @NotBlank(message = "密码不能为空")
    private String password;
}