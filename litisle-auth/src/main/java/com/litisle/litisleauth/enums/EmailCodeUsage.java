package com.litisle.litisleauth.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum EmailCodeUsage {

    /** 账号注册 */
    REGISTER("reg"),

    /** 登录（邮箱+验证码） */
    LOGIN("login"),

    /** 改绑邮箱 */
    CHANGE_EMAIL("bind"),

    /** 找回 / 重置密码 */
    RESET_PASSWORD("reset"),

    /** 注销账号 */
    CANCEL_ACCOUNT("cancel");

    private final String prefix;

}