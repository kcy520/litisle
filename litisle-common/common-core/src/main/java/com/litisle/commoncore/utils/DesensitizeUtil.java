//package com.litisle.commoncore.utils;
//
//
///**
// * 敏感数据脱敏工具
// *但是我的commom-security模块有脱敏注解，一般不需要这个工具类
// * 【使用场景】
// * 1. 手机号/邮箱回显前端（UserSelfVO、LoginUserInfoVO 等）
// * 2. 日志输出含手机号/邮箱的场景（敏感数据不落日志规范）
// *
// * 【规范依据】
// * 敏感字段不回显明文、不落日志；解密仅在安全通道（如 viewCurrentUserPlainPhone）进行
// *
// * @author litisle
// */
//public final class DesensitizeUtil {
//
//    private DesensitizeUtil() {
//    }
//
//    /**
//     * 手机号脱敏：保留前3后4，中间 ****
//     * 如 138****1234
//     *
//     * @param phone 明文手机号
//     * @return 脱敏手机号（null/过短原样返回）
//     */
//    public static String maskPhone(String phone) {
//        if (phone == null || phone.length() < 7) {
//            return phone;
//        }
//        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
//    }
//
//    /**
//     * 邮箱脱敏：保留首字符与域名，如 a***@xx.com
//     *
//     * @param email 明文邮箱
//     * @return 脱敏邮箱（null/无@原样返回）
//     */
//    public static String maskEmail(String email) {
//        if (email == null || !email.contains("@")) {
//            return email;
//        }
//        int atIndex = email.indexOf("@");
//        String name = email.substring(0, atIndex);
//        String domain = email.substring(atIndex);
//        if (name.isEmpty()) {
//            return "***" + domain;
//        }
//        return name.charAt(0) + "***" + domain;
//    }
//}
