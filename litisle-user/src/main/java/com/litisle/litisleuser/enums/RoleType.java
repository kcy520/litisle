package com.litisle.litisleuser.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 角色类型枚举
 * 1-系统内置 2-业务角色
 */
@Getter
public enum RoleType {

    SYSTEM(1, "系统内置"),
    BUSINESS(2, "业务角色");

    @EnumValue
    private final int code;
    private final String desc;

    RoleType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}