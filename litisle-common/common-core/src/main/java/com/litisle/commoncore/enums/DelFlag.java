package com.litisle.commoncore.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 删除标志枚举
 * 0-未删除 1-已删除（对账任务扫 del_flag=1 且超 7 天 → 物理清理）
 */
@Getter
public enum DelFlag {

    /** 未删除 */
    NOT_DELETED(0, "未删除"),

    /** 已删除（逻辑删除） */
    DELETED(1, "已删除");

    /** 入库值（tinyint），MybatisEnumTypeHandler 按 @EnumValue 映射 */
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