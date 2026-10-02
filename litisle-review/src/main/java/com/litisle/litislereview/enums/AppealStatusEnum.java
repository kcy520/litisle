package com.litisle.litislereview.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 申诉状态枚举（appeal.status）
 * <p>0-待裁决 1-维持 2-撤销</p>
 * <p>active_key 生成列按 status=0 判定，裁决后置空不阻断新一轮驳回后的新申诉</p>
 */
@Getter
public enum AppealStatusEnum {

    /** 待裁决 */
    PENDING(0, "待裁决"),

    /** 维持（原判有效） */
    MAINTAINED(1, "维持"),

    /** 撤销（申诉成立，改判 PASS） */
    REVOKED(2, "撤销");

    /** 入库值（tinyint），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final int code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    AppealStatusEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}