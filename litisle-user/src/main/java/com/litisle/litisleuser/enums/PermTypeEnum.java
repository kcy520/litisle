package com.litisle.litisleuser.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 权限类型
 */
@Getter
public enum PermTypeEnum {

    MENU(1, "菜单"),
    BUTTON_API(2, "按钮/API");

    @EnumValue
    private final int code;
    private final String desc;

    PermTypeEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}