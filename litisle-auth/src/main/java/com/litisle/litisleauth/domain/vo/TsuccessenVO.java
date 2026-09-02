package com.litisle.litisleauth.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录 / 注册 / 刷新令牌响应 VO
 *
 * 【使用场景】
 * POST /auth/register
 * POST /auth/login
 * POST /auth/login/sms
 * POST /auth/refresh
 *
 * 【设计说明】
 * 1. 遵循"后端不向前端返回 ID 字段"规范，不返回 userId
 * 2. 双令牌机制：accessToken 短期鉴权 + refreshToken 长期续期
 */
@Data
@Builder
public class TsuccessenVO {

    /** 访问令牌（请求头 Authorization: Bearer xxx） */
    private String accessToken;

    /** 刷新令牌（用于获取新 accessToken，使用后轮换失效） */
    private String refreshToken;

    /** 访问令牌有效期（秒） */
    private Long expiresIn;

    /** 令牌类型（固定 Bearer） */
    private String tokenType;

    /** 用户名 */
    private String username;

    /** 对外UID（用户间搜索唯一标识，随机生成，永久不变） */
    private String uid;

    /** 是否新注册用户（前端可用于引导完善资料） */
    //newUser 是一个业务标记位，让前端区分"刚注册"和"老用户登录"，从而决定要不要引导用户完善资料。
    private Boolean newUser;

    /** 令牌签发时间 */
    private LocalDateTime issuedAt;
}