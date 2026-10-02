package com.litisle.litislereview.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.litisle.commoncore.enums.DictEnum;
import lombok.Getter;

/**
 * 违规类目枚举（moderation_record.category，机审统一映射枚举）
 * <p>politics/porn/abuse/ad/none；LLM 返回的 category 为不可信输出，落库前须白名单结构校验</p>
 * <p>code 为 String，接入 DictEnum 获得 name/code/desc 三值绑定</p>
 */
@Getter
public enum ModerationCategoryEnum implements DictEnum {

    /** 政治敏感 */
    POLITICS("politics", "政治"),

    /** 色情低俗 */
    PORN("porn", "色情"),

    /** 辱骂攻击 */
    ABUSE("abuse", "辱骂"),

    /** 广告 */
    AD("ad", "广告"),

    /** 无违规 */
    NONE("none", "无");

    /** 入库值（varchar(32)） */
    @EnumValue
    private final String code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    ModerationCategoryEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}