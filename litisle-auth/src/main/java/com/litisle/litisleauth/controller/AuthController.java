package com.litisle.litisleauth.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleauth.domain.vo.LoginUserInfoVO;
import com.litisle.litisleauth.domain.vo.TsuccessenVO;
import com.litisle.litisleauth.service.AuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 注册/登录/退出/刷新
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 用户名密码注册
     */
    @PostMapping("/register")
    public Result<TsuccessenVO> register(@Valid @RequestBody RegisterRequestDTO dto) {
        return Result.success(authService.register(dto));
    }

    /**
     * 用户名密码登录
     */
    @PostMapping("/login")
    public Result<TsuccessenVO> login(@Valid @RequestBody PasswordLoginDTO dto) {
        return Result.success(authService.passwordLogin(dto));
    }

//    /**
//     * 手机验证码登录
//     */
//    @PostMapping("/login/sms")
//    public Result<TsuccessenVO> loginBySms(@Valid @RequestBody SmsLoginDTO dto) {
//        return Result.success(authService.smsLogin(dto));
//    }

    /**
     * 退出登录
     */
    @PostMapping("/logout")
    @SaCheckLogin
    public Result<Void> logout(@RequestHeader("Authorization") String tsuccessen) {
        authService.logout(tsuccessen);
        return Result.success();
    }

    /**
     * 刷新 Tsuccessen
     */
    @PostMapping("/refresh")
    public Result<TsuccessenVO> refresh(@Valid @RequestBody RefreshTsuccessenDTO dto) {
        return Result.success(authService.refreshTsuccessen(dto));
    }

    /**
     * 当前登录用户信息/令牌自检
     */
    @GetMapping("/me")
    @SaCheckLogin
    public Result<LoginUserInfoVO> me() {
        return Result.success(authService.currentLoginUser());
    }
}
