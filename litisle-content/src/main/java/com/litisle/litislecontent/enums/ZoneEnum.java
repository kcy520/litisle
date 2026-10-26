package com.litisle.litislecontent.enums;


import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.litisle.commoncore.enums.IntDictEnum;
import lombok.Getter;

/**
 * 文章分区枚举：NORMAL(1)/WRITER(2)
 * <p>推荐流不是分区，是 zone 1/2 聚合视图（阶段12 语义修复：原 RECOMMEND(3) 已删除）</p>
 */
@Getter
public enum ZoneEnum implements IntDictEnum {

    /** 普通分区 */
    NORMAL(1, "普通分区"),

    /** 作家分区 */
    WRITER(2, "作家分区");

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