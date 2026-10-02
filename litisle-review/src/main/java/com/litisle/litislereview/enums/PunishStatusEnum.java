package com.litisle.litislereview.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 处罚流水状态枚举（punish_log.status）
 * <p>1-生效 2-已撤销（申诉成立）</p>
 * <p>本表允许状态列就地更新、行不删（与 moderation_record 的真 append-only 区分）</p>
 */
@Getter
public enum PunishStatusEnum {

    /** 生效 */
    EFFECTIVE(1, "生效"),

    /** 已撤销（申诉成立） */
    REVOKED(2, "已撤销");

    /** 入库值（tinyint），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final int code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    PunishStatusEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}