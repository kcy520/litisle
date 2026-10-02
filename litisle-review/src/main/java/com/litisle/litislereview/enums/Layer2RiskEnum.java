package com.litisle.litislereview.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 小审（LLM）风险枚举（review_task.layer2_risk）
 * <p>0-未送审 1-低 2-中 3-高（辅助风险分级：由 LLM 适配器写入供人审队列排序，
 * 不承载判定语义，判定以 mod_verdict 为准）</p>
 */
@Getter
public enum Layer2RiskEnum {

    /** 未送审 */
    NOT_SUBMITTED(0, "未送审"),

    /** 低风险 */
    LOW(1, "低"),

    /** 中风险 */
    MID(2, "中"),

    /** 高风险 */
    HIGH(3, "高");

    /** 入库值（tinyint），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final int code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    Layer2RiskEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}