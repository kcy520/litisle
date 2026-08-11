//package com.litisle.litisleauth.controller;
//
////图形验证码
//@RestController
//@RequestMapping("/v1/auth/captcha")
//public class CaptchaController {
//
//    private final CaptchaService captchaService;
//
//    public CaptchaController(CaptchaService captchaService) {
//        this.captchaService = captchaService;
//    }
//
//    /**
//     * 获取图形验证码
//     */
//    @GetMapping
//    public CommonResult<CaptchaVO> getCaptcha() {
//        return CommonResult.ok(captchaService.generate());
//    }
//}
