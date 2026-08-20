//package com.litisle.litisleauth.domain.dto;
//
//@Data
//public class SmsLoginDTO {
//
//    /**
//     * 手机号
//     */
//    @NotBlank(message = "手机号不能为空")
//    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
//    private String phone;
//
//    /**
//     * 短信验证码
//     */
//    @NotBlank(message = "验证码不能为空")
//    @Size(min = 4, max = 6, message = "验证码长度 4-6 位")
//    private String code;
//
//    /** 是否记住我 */
//    private Boolean rememberMe = false;
//}