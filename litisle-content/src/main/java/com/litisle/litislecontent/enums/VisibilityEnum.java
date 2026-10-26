package com.litisle.litislecontent.enums;


import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.litisle.commoncore.enums.IntDictEnum;
import lombok.Getter;

/**
 * 文章可见性枚举：PUBLIC(1)/PRIVATE(2)/FOLLOWERS(3)
 * <p>阶段9：密码可见（PASSWORD/4）功能已下线，枚举值删除，存量 4 已迁移为 1（公开）</p>
 */
@Getter
public enum VisibilityEnum implements IntDictEnum {

    /** 公开 */
    PUBLIC(1, "公开"),

    /** 仅自己可见 */
    PRIVATE(2, "仅自己可见"),

    /** 仅关注者可见 */
    FOLLOWERS(3, "仅关注者可见");

    /** 入库值（tinyint），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final int code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    VisibilityEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}