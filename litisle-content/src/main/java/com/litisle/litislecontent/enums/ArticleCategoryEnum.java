package com.litisle.litislecontent.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 文章分类枚举：小说/散文/现代诗/随笔（平台受控封闭集，区别于开放词汇标签 tag）
 * <p>入库值为中文分类名（DB varchar(20) default '散文' 直存口径），@EnumValue 直接映射，
 * 无需历史数据迁移；JSON 出入参均为中文分类名（@JsonValue），前端无感</p>
 */
@Getter
public enum ArticleCategoryEnum {

    /** 小说 */
    NOVEL("小说"),

    /** 散文 */
    ESSAY("散文"),

    /** 现代诗 */
    MODERN_POEM("现代诗"),

    /** 随笔 */
    ESSAY_NOTE("随笔");

    /** 入库值 & JSON 序列化值（中文分类名），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    @JsonValue
    private final String name;

    /** 缺省分类（发布/存草稿 category 可空时落「散文」） */
    public static final ArticleCategoryEnum DEFAULT = ESSAY;

    ArticleCategoryEnum(String name) {
        this.name = name;
    }

    /**
     * 按中文分类名静态解析（查询白名单校验唯一入口，避免散落遍历）
     *
     * @param name 中文分类名（可 null/空白）
     * @return 命中的枚举；未命中或入参无效返回 null（由调用方决定报错/放行语义）
     */
    public static ArticleCategoryEnum resolve(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (ArticleCategoryEnum e : values()) {
            if (e.name.equals(name)) {
                return e;
            }
        }
        return null;
    }

    /**
     * 中文分类名白名单校验（listPublic 过滤入参前置闸门，防任意字符串静默空结果）
     *
     * @param name 中文分类名（可 null/空白）
     * @return true=合法分类；false=枚举外脏值
     */
    public static boolean isValid(String name) {
        return resolve(name) != null;
    }
}