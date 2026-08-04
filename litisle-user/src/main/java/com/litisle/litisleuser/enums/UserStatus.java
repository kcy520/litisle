package com.litisle.litisleuser.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 用户状态枚举
 * 0-禁用 1-正常 2-注销
 */
@Getter
public enum UserStatus {

    DISABLED(0, "禁用"),
    NORMAL(1, "正常"),
    CANCELLED(2, "注销");

    @EnumValue
    private final int code;
    private final String desc;

    UserStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}