package com.litisle.litislereview.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.litisle.commoncore.enums.DictEnum;
import lombok.Getter;

/**
 * 敏感词库场景枚举（sensitive_word_version.scene / sensitive_word_library.scene）
 * <p>仅文本场景走 DFA：WordLibraryLoader 只加载 ARTICLE/COMMENT/USERNAME/MESSAGE，
 * 图片场景（IMAGE/ARTICLE_IMG_RE_REVIEW）查词库为空属预期，跳过不报错</p>
 * <p>code 为 String，接入 DictEnum 获得 name/code/desc 三值绑定</p>
 */
@Getter
public enum WordSceneEnum implements DictEnum {

    /** 文章（评论加载白名单） */
    ARTICLE("ARTICLE", "文章"),

    /** 评论（加载白名单） */
    COMMENT("COMMENT", "评论"),

    /** 用户名（不加载白名单） */
    USERNAME("USERNAME", "用户名"),

    /** 私信 */
    MESSAGE("MESSAGE", "私信");

    /** 入库值（varchar(20)） */
    @EnumValue
    private final String code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    WordSceneEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}