package com.litisle.litislecontent.enums;


import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.litisle.commoncore.enums.IntDictEnum;
import lombok.Getter;

/**
 * 标签状态枚举：NORMAL(0)/BANNED(1)
 * <p>BANNED 为违规标签运营处置，封禁后列表侧不再聚合展示</p>
 */
@Getter
public enum TagStatusEnum implements IntDictEnum {

    /** 正常 */
    NORMAL(0, "正常"),

    /** 封禁（违规标签运营处置） */
    BANNED(1, "封禁");

    /** 入库值（tinyint），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final int code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    TagStatusEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}