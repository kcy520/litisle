package com.litisle.litisleuser.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
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

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    AvatarColor(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 反序列化：兼容前端传 desc（如"金色"）或 code（如 1）
     */
    @JsonCreator
    public static AvatarColor of(String value) {
        if (value == null) {
            return null;
        }
        for (AvatarColor c : values()) {
            if (c.desc.equals(value)) {
                return c;
            }
        }
        try {
            int code = Integer.parseInt(value);
            for (AvatarColor c : values()) {
                if (c.code == code) {
                    return c;
                }
            }
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException("未知的头像标识: " + value);
    }
}