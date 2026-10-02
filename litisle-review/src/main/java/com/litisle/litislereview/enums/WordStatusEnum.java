package com.litisle.litislereview.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 敏感词启停状态枚举（sensitive_word_library.status）
 * <p>0-禁用 1-启用；启停变更会在同一事务内使 scene 版本 +1 触发 DFA 重建</p>
 */
@Getter
public enum WordStatusEnum {

    /** 禁用 */
    DISABLED(0, "禁用"),

    /** 启用 */
    ENABLED(1, "启用");

    /** 入库值（tinyint），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final int code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    WordStatusEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}