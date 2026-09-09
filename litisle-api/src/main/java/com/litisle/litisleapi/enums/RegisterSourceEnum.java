package com.litisle.litisleapi.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
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

    /**
     * 根据 code 反查枚举，找不到返回 null
     * @JsonCreator：接管该枚举的反序列化，兼容前端传空字符串/未知编码的情况
     * 避免抛出 Cannot coerce empty String ("") to Enum value（仅依赖 jackson-annotations，全 Jackson 2.x 版本兼容）
     */
    @JsonCreator//“反序列化 JSON 时，用这个构造方法 / 工厂方法来创建对象，而不是用默认无参构造器。”
    public static RegisterSourceEnum fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (RegisterSourceEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}