package com.litisle.litisleuser.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 权限状态：0-禁用 1-正常
 */
@Getter
public enum PermStatusEnum {

    DISABLED(0, "禁用"),
    ENABLED(1, "正常");

    @EnumValue
    private final int code;
    private final String desc;

    PermStatusEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}