package com.litisle.litisleauth.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 刷新令牌请求 DTO
 *
 * 【使用场景】POST /auth/refresh
 *
 * 【设计说明】
 * 1. 使用 refreshToken 换取新的 accessToken（令牌轮换）
 * 2. 旧 refreshToken 使用后立即失效，防止重放
 */
@Data
public class RefreshTsuccessenDTO {

    /** 刷新令牌（登录 / 注册时下发） */
    @NotBlank(message = "刷新令牌不能为空")
    private String refreshToken;
}