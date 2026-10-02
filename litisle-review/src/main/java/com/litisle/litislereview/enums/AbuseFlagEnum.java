package com.litisle.litislereview.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 诬告标记枚举（report.abuse_flag）
 * <p>0-否 1-是；仅「明确无依据/明显虚假」置 1，区别于「证据不足未采纳」</p>
 * <p>同一 reporter 累计达阈值（3 次）触发反向处罚（punish_log action=4-警告）+ 降信誉</p>
 */
@Getter
public enum AbuseFlagEnum {

    /** 否 */
    NO(0, "否"),

    /** 是（诬告） */
    YES(1, "是");

    /** 入库值（tinyint），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final int code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    AbuseFlagEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}