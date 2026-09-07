package com.litisle.litisleapi.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum RegisterSourceEnum {

    WEB("WEB", "网页端"),
    APP("APP", "手机 App"),
    MINI_PROGRAM("MINI_PROGRAM", "微信小程序"),
    ;

    @JsonValue
    private final String code;
    private final String desc;

    RegisterSourceEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 根据 code 反查枚举，找不到返回 null */
    public static RegisterSourceEnum fromCode(String code) {
        for (RegisterSourceEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}