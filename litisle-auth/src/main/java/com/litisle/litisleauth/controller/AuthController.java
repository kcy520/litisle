package com.litisle.litisleauth.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleauth.domain.dto.EmailLoginDTO;
import com.litisle.litisleauth.domain.dto.PasswordLoginDTO;
import com.litisle.litisleauth.domain.vo.TsuccessenVO;
import com.litisle.litisleauth.domain.vo.LoginUserInfoVO;
import com.litisle.litisleauth.domain.dto.RegisterRequestDTO;
import com.litisle.litisleauth.domain.dto.RefreshTsuccessenDTO;
import com.litisle.litisleauth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

// 注册/登录/退出/刷新
@Tag(name = "认证接口", description = "账号Auth")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 用户名密码注册（注册新用户）
     */
    @Operation(summary = "用户名密码注册")
    @PostMapping("/register")
    public Result<TsuccessenVO> register(@Valid @RequestBody RegisterRequestDTO dto) {
        TsuccessenVO register = authService.register(dto);
        return Result.success(register);
    }

    /**
     * 用户名密码登录（登录）
     */
    @Operation(summary = "用户名密码登录")
    @PostMapping("/login")
    public Result<TsuccessenVO> login(@Valid @RequestBody PasswordLoginDTO dto) {
        TsuccessenVO tsuccessenVO = authService.passwordLogin(dto);
        return Result.success(tsuccessenVO);
    }

//    /**
//     * 手机验证码登录
//     */
//    @PostMapping("/login/sms")
//    public Result<TsuccessenVO> loginBySms(@Valid @RequestBody SmsLoginDTO dto) {
//        return Result.success(authService.smsLogin(dto));
//    }

        /**
     * 邮箱+验证码登录（邮箱登录）
     */
    @Operation(summary = "邮箱+验证码登录")
    @PostMapping("/login/sms")
    public Result<TsuccessenVO> loginBySms(@Valid @RequestBody EmailLoginDTO dto) {
        TsuccessenVO tsuccessenVO = authService.emailLogin(dto);
        return Result.success(tsuccessenVO);
    }

    /**
     * 退出登录
     */
    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    @SaCheckLogin
    public Result<Void> logout(@RequestHeader("Authorization") String tsuccessen) {
        authService.logout(tsuccessen);
        return Result.success();
    }

    /**
     * 刷新 Tsuccessen 即刷新令牌（令牌刷新）
     */
    @Operation(summary = "刷新令牌")
    @PostMapping("/refresh")
    public Result<TsuccessenVO> refresh(@Valid @RequestBody RefreshTsuccessenDTO dto) {
        TsuccessenVO tsuccessenVO = authService.refreshTsuccessen(dto);
        return Result.success(tsuccessenVO);
    }

    /**
     * 当前登录用户信息/令牌自检
     */
    @Operation(summary = "当前登录用户信息", description = "令牌自检")
    @GetMapping("/me")
    @SaCheckLogin
    public Result<LoginUserInfoVO> me() {
        return Result.success(authService.currentLoginUser());
    }
}
