package com.litisle.litislereview.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 处罚动作枚举（punish_log.action）
 * <p>1-禁言 2-封禁 3-内容下架（含机审首发驳回，此时内容从未发布）4-警告</p>
 * <p>自动禁言/封禁阈值判定只重放 action∈{1,2} 的生效行；机审 action=3 不驱动真实封禁</p>
 */
@Getter
public enum PunishActionEnum {

    /** 禁言 */
    MUTE(1, "禁言"),

    /** 封禁 */
    BAN(2, "封禁"),

    /** 内容下架（含机审首发驳回） */
    CONTENT_TAKEDOWN(3, "内容下架"),

    /** 警告 */
    WARNING(4, "警告");

    /** 入库值（tinyint），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final int code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    PunishActionEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}