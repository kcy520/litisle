package com.litisle.litisleauth.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录 / 注册 / 刷新令牌返回信息
 */
@Data
@Builder
public class TsuccessenVO {

    /** 访问令牌（Sa-Token accessToken） */
    private String accessToken;

    /** 刷新令牌（用于获取新 accessToken） */
    private String refreshToken;

    /** 访问令牌有效期（秒） */
    private Long expiresIn;

    /** 用户ID */
    private Long userId;

    /** 用户名 */
    private String username;

    /** 是否新注册用户（前端可用于引导流程） */
    private Boolean newUser;

    /** 令牌签发时间 */
    private LocalDateTime issuedAt;
}