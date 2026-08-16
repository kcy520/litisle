package com.litisle.litisleapi.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OAuth 授权地址响应 VO
 *
 * 【使用场景】
 * - 前端请求 /v1/auth/oauth2/{provider}/authorize-url
 * - 后端返回第三方登录的授权页面 URL
 * - 前端 redirect 到该 URL，用户完成第三方认证后回调
 *
 * @author litisle
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuthAuthorizeVO {

    /**
     * 第三方授权页面 URL
     * 前端直接 window.location.href = authorizeUrl 即可跳转
     */
    private String authorizeUrl;

    /**
     * OAuth 提供方
     * 如：WECHAT_OPEN（微信开放平台）、QQ、GITHUB 等
     */
    private String provider;

    /**
     * 状态码（state）
     * 用于防止 CSRF 攻击，回调时需校验一致性
     * 后端生成并缓存到 Redis，回调时验证
     */
    private String state;
}