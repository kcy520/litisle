package com.litisle.litislereview.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 机审三分态枚举（review_task.mod_verdict / moderation_record.verdict 共用）
 * <p>0-未审 1-PASS 2-REJECT 3-SUSPICIOUS</p>
 * <p>review_task.mod_verdict 为冗余列供人审队列列表直读（免 JOIN moderation_record），
 * 0-未审仅任务侧出现；moderation_record.verdict 只写 1/2/3</p>
 */
@Getter
public enum VerdictEnum {

    /** 未审（仅 review_task.mod_verdict 出现） */
    NONE(0, "未审"),

    /** PASS（通过） */
    PASS(1, "通过"),

    /** REJECT（驳回） */
    REJECT(2, "驳回"),

    /** SUSPICIOUS（可疑，转人审） */
    SUSPICIOUS(3, "可疑");

    /** 入库值（tinyint），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final int code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    VerdictEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}