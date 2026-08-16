package com.litisle.litisleapi.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * JWT 双 Token 响应 VO
 *
 * 【设计说明】
 * 1. Access Token（短期）：用于 API 鉴权，有效期短（如 30 分钟）
 * 2. Refresh Token（长期）：用于刷新 Access Token，有效期长（如 7 天）
 * 3. 双 Token 机制优势：
 *    - Access Token 短有效，泄漏后攻击窗口小
 *    - Refresh Token 可更换，用户无感知续期
 *    - 退出登录时仅将 Access Token 加入黑名单，Refresh Token 仍需校验
 *
 * @author litisle
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenVO {

    /**
     * 访问令牌（Access Token）
     * 用于 API 鉴权，放在 Authorization Header 中
     * 格式：Bearer {accessToken}
     */
    private String accessToken;

    /**
     * 刷新令牌（Refresh Token）
     * 用于刷新 Access Token，不可用于 API 鉴权
     * 有效期通常为 7 天
     */
    private String refreshToken;

    /**
     * Access Token 过期时间（秒）
     * 前端可根据此值提前刷新 Token，避免 401 中断
     * 示例：1800（30 分钟）
     */
    private Long expiresIn;

    /**
     * Token 类型
     * 固定值："Bearer"
     */
    private String tokenType;
}