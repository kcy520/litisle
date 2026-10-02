package com.litisle.litislereview.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 审核任务状态枚举（review_task.status，状态机核心）
 * <p>0-待审 1-自动通过 2-人工待审 3-通过 4-驳回</p>
 * <p>终态为 {1,3,4}；decide 用 CAS（where id=? and status=2）天然拦截重复裁决</p>
 */
@Getter
public enum ReviewTaskStatusEnum {

    /** 待审（初始态，待机审） */
    PENDING(0, "待审"),

    /** 自动通过（机审 PASS 直落） */
    AUTO_PASS(1, "自动通过"),

    /** 人工待审（SUSPICIOUS 进人审队列） */
    MANUAL_PENDING(2, "人工待审"),

    /** 通过（人审终态） */
    PASS(3, "通过"),

    /** 驳回（人审终态） */
    REJECT(4, "驳回");

    /** 入库值（tinyint），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final int code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    ReviewTaskStatusEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}