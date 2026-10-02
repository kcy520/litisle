package com.litisle.litislereview.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 敏感词名单类型枚举（sensitive_word_library.is_blacklist）
 * <p>0-白名单 1-黑名单</p>
 * <p>对应差异化策略：评论加载白名单 / 用户名不加载白名单，按 scene 过滤加载</p>
 */
@Getter
public enum WordListTypeEnum {

    /** 白名单 */
    WHITELIST(0, "白名单"),

    /** 黑名单 */
    BLACKLIST(1, "黑名单");

    /** 入库值（tinyint），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final int code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    WordListTypeEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}