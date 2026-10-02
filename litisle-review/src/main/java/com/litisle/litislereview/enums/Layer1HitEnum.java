package com.litisle.litislereview.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * DFA 一级命中枚举（review_task.layer1_hit）
 * <p>0-未命中 1-HIGH 2-MID</p>
 */
@Getter
public enum Layer1HitEnum {

    /** 未命中 */
    NONE(0, "未命中"),

    /** HIGH 命中（高危） */
    HIGH(1, "高危"),

    /** MID 命中（中危） */
    MID(2, "中危");

    /** 入库值（tinyint），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final int code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    Layer1HitEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}