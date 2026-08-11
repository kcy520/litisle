//package com.litisle.litisleauth.controller;
//
////短信验证码
//@RestController
//@RequestMapping("/v1/auth/sms")
//public class SmsController {
//
//    private final SmsCodeService smsCodeService;
//
//    public SmsController(SmsCodeService smsCodeService) {
//        this.smsCodeService = smsCodeService;
//    }
//
//    /**
//     * 发送手机验证码
//     */
//    @PostMapping("/code")
//    public CommonResult<Void> sendCode(@Valid @RequestBody SendSmsCodeDTO dto) {
//        smsCodeService.send(dto);
//        return CommonResult.ok();
//    }
//}