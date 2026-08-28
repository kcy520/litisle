package com.litisle.commoncore.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
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

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    UserStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 反序列化：兼容前端传 desc（如"正常"）或 code（如 1）
     */
    @JsonCreator
    public static UserStatus of(String value) {
        if (value == null) {
            return null;
        }
        for (UserStatus s : values()) {
            if (s.desc.equals(value)) {
                return s;
            }
        }
        try {
            int code = Integer.parseInt(value);
            for (UserStatus s : values()) {
                if (s.code == code) {
                    return s;
                }
            }
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException("未知的用户状态: " + value);
    }
}