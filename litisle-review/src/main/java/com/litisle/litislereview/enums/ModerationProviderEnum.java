package com.litisle.litislereview.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.litisle.commoncore.enums.DictEnum;
import lombok.Getter;

/**
 * 审核提供方枚举（moderation_record.provider）
 * <p>图片：onnx/aliyun；文本：llm；人工/DFA 为空</p>
 * <p>缓存兜底按此过滤，防 onnx→aliyun 切换复用旧结论</p>
 * <p>code 为 String，接入 DictEnum 获得 name/code/desc 三值绑定</p>
 */
@Getter
public enum ModerationProviderEnum implements DictEnum {

    /** 本地 ONNX 模型 */
    ONNX("onnx", "本地ONNX"),

    /** 阿里云内容安全 */
    ALIYUN("aliyun", "阿里云"),

    /** 大模型（LLM 语义审核） */
    LLM("llm", "大模型");

    /** 入库值（varchar(32)） */
    @EnumValue
    private final String code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    ModerationProviderEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}