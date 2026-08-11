//package com.litisle.litisleauth.controller;
//
//
////// 微信/QQ 第三方登录
//@RestController
//@RequestMapping("/v1/auth/oauth2")
//public class OAuth2Controller {
//
//    private final OAuth2LoginService oAuth2LoginService;
//
//    public OAuth2Controller(OAuth2LoginService oAuth2LoginService) {
//        this.oAuth2LoginService = oAuth2LoginService;
//    }
//
//    /**
//     * 获取微信/QQ 授权地址
//     */
//    @GetMapping("/{provider}/authorize-url")
//    public CommonResult<OAuthAuthorizeVO> authorizeUrl(@PathVariable String provider) {
//        return CommonResult.ok(oAuth2LoginService.buildAuthorizeUrl(provider));
//    }
//
//    /**
//     * 第三方登录回调
//     */
//    @GetMapping("/{provider}/callback")
//    public CommonResult<TokenVO> callback(
//            @PathVariable String provider,
//            @RequestParam Map<String, String> params
//    ) {
//        return CommonResult.ok(oAuth2LoginService.handleCallback(provider, params));
//    }
//
//    /**
//     * 绑定第三方账号到当前用户
//     */
//    @PostMapping("/bind")
//    @PreAuthorize("isAuthenticated()")
//    public CommonResult<Void> bind(@Valid @RequestBody OAuthBindDTO dto) {
//        oAuth2LoginService.bind(dto);
//        return CommonResult.ok();
//    }
//
//    /**
//     * 解绑第三方账号
//     */
//    @DeleteMapping("/unbind/{provider}")
//    @PreAuthorize("isAuthenticated()")
//    public CommonResult<Void> unbind(@PathVariable String provider) {
//        oAuth2LoginService.unbind(provider);
//        return CommonResult.ok();
//    }
//}