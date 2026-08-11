//package com.litisle.litisleauth.controller;
//
//// 注册/登录/退出/刷新
//@RestController
//@RequestMapping("/v1/auth")
//public class AuthController {
//
//    private final AuthService authService;
//
//    public AuthController(AuthService authService) {
//        this.authService = authService;
//    }
//
//    /**
//     * 用户名密码注册
//     */
//    @PostMapping("/register")
//    public CommonResult<TokenVO> register(@Valid @RequestBody RegisterRequestDTO dto) {
//        return CommonResult.ok(authService.register(dto));
//    }
//
//    /**
//     * 用户名密码登录
//     */
//    @PostMapping("/login")
//    public CommonResult<TokenVO> login(@Valid @RequestBody PasswordLoginDTO dto) {
//        return CommonResult.ok(authService.passwordLogin(dto));
//    }
//
//    /**
//     * 手机验证码登录
//     */
//    @PostMapping("/login/sms")
//    public CommonResult<TokenVO> loginBySms(@Valid @RequestBody SmsLoginDTO dto) {
//        return CommonResult.ok(authService.smsLogin(dto));
//    }
//
//    /**
//     * 退出登录
//     */
//    @PostMapping("/logout")
//    @PreAuthorize("isAuthenticated()")
//    public CommonResult<Void> logout(@RequestHeader("Authorization") String token) {
//        authService.logout(token);
//        return CommonResult.ok();
//    }
//
//    /**
//     * 刷新 Token
//     */
//    @PostMapping("/refresh")
//    public CommonResult<TokenVO> refresh(@Valid @RequestBody RefreshTokenDTO dto) {
//        return CommonResult.ok(authService.refreshToken(dto));
//    }
//
//    /**
//     * 当前登录用户信息/令牌自检
//     */
//    @GetMapping("/me")
//    @PreAuthorize("isAuthenticated()")
//    public CommonResult<LoginUserInfoVO> me() {
//        return CommonResult.ok(authService.currentLoginUser());
//    }
//}
