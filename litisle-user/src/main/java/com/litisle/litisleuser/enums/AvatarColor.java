package com.litisle.litisleuser.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 头像标识枚举
 * 0-无 1-金色（作家）2-白色（纪念作家）
 */
@Getter
public enum AvatarColor {

    NONE(0, "无"),
    GOLD(1, "金色"),
    WHITE(2, "白色");

    @EnumValue
    private final int code;
    private final String desc;

    AvatarColor(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}