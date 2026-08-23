package com.litisle.litisleauth.controller;

import com.litisle.commoncore.result.Result;
import com.litisle.commonredis.ratelimit.RateLimiter;
import com.litisle.litisleauth.domain.dto.SendEmailCodeDTO;
import com.litisle.litisleauth.domain.dto.VerifyEmailCodeDTO;
import com.litisle.litisleauth.domain.dto.VerifyImgCodeDTO;
import com.litisle.litisleauth.domain.vo.CaptchaImgVO;
import com.litisle.litisleauth.service.CaptchaService;
import com.litisle.litisleauth.utils.IpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * 验证码接口
 *
 * 【接口清单】
 * 1. GET  /auth/captcha/img          获取图形验证码
 * 2. POST /auth/captcha/email        发送邮箱验证码
 * 3. POST /auth/captcha/img/verify   校验图形验证码
 * 4. POST /auth/captcha/email/verify 校验邮箱验证码
 *
 * 【安全设计】
 * - 限流：@RateLimiter 基于 Redis + Lua 固定窗口计数，按 IP 限流（匿名接口）
 * - 防刷：邮箱验证码发送在 service 层按 IP 二次计数（纵深防御）
 * - 防爆破：邮箱验证码校验失败达阈值后删除，强制重新获取
 */
@Tag(name = "验证码接口", description = "图形验证码 / 邮箱验证码的获取与校验")
@RestController
@RequestMapping("/auth/captcha")
@Slf4j
public class CaptchaController {

    private final CaptchaService captchaService;

    public CaptchaController(CaptchaService captchaService) {
        this.captchaService = captchaService;
    }

    /**
     * 获取图形验证码
     *
     * @return uuid + base64 图片（不含验证码文本，防止前端/抓包直接读取）
     */
    @Operation(summary = "获取图形验证码")
    @RateLimiter(time = 1, timeUnit = TimeUnit.MINUTES, count = 10, keyType = RateLimiter.KeyType.IP)
    @GetMapping("/img")
    public Result<CaptchaImgVO> getImgCode() {
        CaptchaImgVO captchaImgVO = captchaService.generateImgCode();
        return Result.success(captchaImgVO);
    }

    /**
     * 发送邮箱验证码
     *
     * @param dto     邮箱 + 验证码用途
     * @param request 用于获取客户端 IP（防刷）
     */
    @Operation(summary = "发送邮箱验证码")
    @RateLimiter(time = 1, timeUnit = TimeUnit.MINUTES, count = 5, keyType = RateLimiter.KeyType.IP)
    @PostMapping("/email")
    public Result<Void> sendEmailCode(@Valid @RequestBody SendEmailCodeDTO dto, HttpServletRequest request) {
        captchaService.sendEmailCode(dto.getEmail(), dto.getUsage(), IpUtil.getClientIp(request));
        return Result.success();
    }

    /**
     * 校验图形验证码（一次性，校验后立即失效）
     *
     * @param dto uuid + 验证码
     */
    @Operation(summary = "校验图形验证码(一次性)")
    @RateLimiter(time = 1, timeUnit = TimeUnit.MINUTES, count = 30, keyType = RateLimiter.KeyType.IP)
    @PostMapping("/img/verify")
    public Result<Void> verifyImgCode(@Valid @RequestBody VerifyImgCodeDTO dto) {
        captchaService.verifyImgCode(dto.getUuid(), dto.getCode());
        return Result.success();
    }

    /**
     * 校验邮箱验证码（一次性，校验后立即失效）
     *
     * @param dto 邮箱 + 用途 + 验证码
     */
    @Operation(summary = "校验邮箱验证码(一次性)")
    @RateLimiter(time = 1, timeUnit = TimeUnit.MINUTES, count = 30, keyType = RateLimiter.KeyType.IP)
    @PostMapping("/email/verify")
    public Result<Void> verifyEmailCode(@Valid @RequestBody VerifyEmailCodeDTO dto) {
        captchaService.verifyEmailCode(dto.getEmail(), dto.getUsage(), dto.getCode());
        return Result.success();
    }
}
