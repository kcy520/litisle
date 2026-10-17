package com.litisle.litislecontent.enums;


import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.litisle.commoncore.enums.IntDictEnum;
import lombok.Getter;

/**
 * 文章状态枚举：DRAFT(0)/PENDING_REVIEW(1)/PUBLISHED(2)/REJECTED(3)/TAKEN_DOWN(4)
 */
@Getter
public enum ArticleStatusEnum implements IntDictEnum {

    /** 草稿 */
    DRAFT(0, "草稿"),

    /** 待审核 */
    PENDING_REVIEW(1, "待审核"),

    /** 已发布 */
    PUBLISHED(2, "已发布"),

    /** 驳回 */
    REJECTED(3, "驳回"),

    /** 下架 */
    TAKEN_DOWN(4, "下架");

    /** 入库值（tinyint），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final int code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    ArticleStatusEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}