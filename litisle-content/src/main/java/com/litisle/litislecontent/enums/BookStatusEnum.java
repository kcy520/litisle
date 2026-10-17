package com.litisle.litislecontent.enums;


import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.litisle.commoncore.enums.IntDictEnum;
import lombok.Getter;

/**
 * 书籍状态枚举：SERIALING(0)/COMPLETED(1)/TAKEN_DOWN(2)（默认连载中）
 */
@Getter
public enum BookStatusEnum implements IntDictEnum {

    /** 连载中 */
    SERIALING(0, "连载中"),

    /** 已完结 */
    COMPLETED(1, "已完结"),

    /** 下架 */
    TAKEN_DOWN(2, "下架");

    /** 入库值（tinyint），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final int code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    BookStatusEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}