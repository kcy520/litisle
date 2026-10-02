package com.litisle.litislereview.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 举报状态枚举（report.status）
 * <p>0-待处理 1-初审中 2-转人工 3-已裁决 4-已撤回</p>
 * <p>active_key 生成列按 status<3 判定，4-已撤回亦置空允许再举报</p>
 */
@Getter
public enum ReportStatusEnum {

    /** 待处理 */
    PENDING(0, "待处理"),

    /** 初审中（机审在跑） */
    REVIEWING(1, "初审中"),

    /** 转人工（已建 review_task 并回填 review_task_id） */
    MANUAL(2, "转人工"),

    /** 已裁决（decide 闭环回写） */
    DECIDED(3, "已裁决"),

    /** 已撤回（仅举报人本人、status<3 可操作） */
    WITHDRAWN(4, "已撤回");

    /** 入库值（tinyint），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final int code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    ReportStatusEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}