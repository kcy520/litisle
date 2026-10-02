package com.litisle.litislereview.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 上次处罚动作枚举（violation_score.last_punish_action）
 * <p>0-无 1-禁言 2-封禁（只记影响登录态的处罚）</p>
 * <p>punish_log.action 的 3-内容下架/4-警告 仅落流水不改本表，枚举不对齐系有意设计</p>
 */
@Getter
public enum LastPunishActionEnum {

    /** 无 */
    NONE(0, "无"),

    /** 禁言 */
    MUTE(1, "禁言"),

    /** 封禁 */
    BAN(2, "封禁");

    /** 入库值（tinyint），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final int code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    LastPunishActionEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}