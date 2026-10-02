package com.litisle.litislereview.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 审核方式枚举（moderation_record.mod_type）
 * <p>1-关键词DFA 2-LLM语义 3-本地图片模型 4-云API 5-人工复审 6-申诉改判</p>
 */
@Getter
public enum ModTypeEnum {

    /** 关键词 DFA */
    DFA(1, "关键词DFA"),

    /** LLM 语义审核 */
    LLM(2, "LLM语义"),

    /** 本地图片模型（ONNX） */
    LOCAL_IMAGE_MODEL(3, "本地图片模型"),

    /** 云 API（如阿里云内容安全） */
    CLOUD_API(4, "云API"),

    /** 人工复审 */
    MANUAL_REVIEW(5, "人工复审"),

    /** 申诉改判 */
    APPEAL_REJUDGE(6, "申诉改判");

    /** 入库值（tinyint），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final int code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    ModTypeEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}