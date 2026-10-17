package com.litisle.litislecontent.enums;


import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.litisle.commoncore.enums.IntDictEnum;
import lombok.Getter;

/**
 * 文章可见性枚举：PUBLIC(1)/PRIVATE(2)/FOLLOWERS(3)/PASSWORD(4)
 * <p>PASSWORD(4) 时密码存 article.access_password（BCrypt 加密）</p>
 */
@Getter
public enum VisibilityEnum implements IntDictEnum {

    /** 公开 */
    PUBLIC(1, "公开"),

    /** 仅自己可见 */
    PRIVATE(2, "仅自己可见"),

    /** 仅关注者可见 */
    FOLLOWERS(3, "仅关注者可见"),

    /** 密码访问（密码存 access_password） */
    PASSWORD(4, "密码访问");

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