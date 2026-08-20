package com.litisle.litisleuser.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 删除标志枚举
 * 0-未删除 1-已删除
 */
@Getter
public enum DelFlag {

    NOT_DELETED(0, "未删除"),
    DELETED(1, "已删除");

    @EnumValue
    private final int code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    DelFlag(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}