package com.litisle.litislereview.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 敏感词级别枚举（sensitive_word_library.level）
 * <p>1-HIGH 2-MID</p>
 */
@Getter
public enum WordLevelEnum {

    /** HIGH（高危） */
    HIGH(1, "高危"),

    /** MID（中危） */
    MID(2, "中危");

    /** 入库值（tinyint），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final int code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    WordLevelEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}