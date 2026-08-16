package com.litisle.litisleapi.dto.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OAuth 第三方账号绑定请求 DTO
 *
 * 【使用场景】
 * 1. 已登录用户将第三方账号绑定到当前账号
 * 2. 第三方登录时，如果 openId 未绑定，弹窗让用户选择绑定已有账号
 *
 * 【设计说明】
 * - provider 和 openId 由前端传入（后端在回调时已返回给前端）
 * - 后端校验 provider 是否支持、openId 是否已被其他用户绑定
 *
 * @author litisle
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuthBindDTO {

    /**
     * OAuth 提供方
     * 如：WECHAT_OPEN、QQ、GITHUB
     * 不可为空
     */
    @NotBlank(message = "OAuth 提供方不能为空")
    private String provider;

    /**
     * 第三方 openId
     * 第三方平台返回的用户唯一标识
     * 不可为空
     */
    @NotBlank(message = "openId 不能为空")
    private String openId;

    /**
     * 第三方 unionId（可选）
     * 微信开放平台特有，用于关联同一开放平台下的多个应用
     */
    private String unionId;

    /**
     * 授权码（authCode）
     * 回调时第三方返回的临时授权码
     * 用于后端校验绑定的合法性
     */
    @NotBlank(message = "授权码不能为空")
    private String authCode;
}