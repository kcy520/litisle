package com.litisle.litislereview.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 作家申请状态枚举（author_apply.status）
 * <p>0-待审 1-通过 2-驳回</p>
 */
@Getter
public enum AuthorApplyStatusEnum {

    /** 待审 */
    PENDING(0, "待审"),

    /** 通过 */
    PASS(1, "通过"),

    /** 驳回 */
    REJECT(2, "驳回");

    /** 入库值（tinyint），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final int code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    AuthorApplyStatusEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}