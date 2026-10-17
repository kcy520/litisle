package com.litisle.litislecontent.enums;


import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.litisle.commoncore.enums.IntDictEnum;
import lombok.Getter;

/**
 * 文章分区枚举：NORMAL(1)/WRITER(2)/RECOMMEND(3)
 */
@Getter
public enum ZoneEnum implements IntDictEnum {

    /** 普通分区 */
    NORMAL(1, "普通分区"),

    /** 作家分区 */
    WRITER(2, "作家分区"),

    /** 推荐流（运营/算法推荐内容） */
    RECOMMEND(3, "推荐流");

    /** 入库值（tinyint），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final int code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    ZoneEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}