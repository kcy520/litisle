package com.litisle.litislesearch.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import com.litisle.commoncore.enums.DictEnum;
import lombok.Getter;

/**
 * 检索对象类型枚举（决定路由到哪个 ES 索引，架构文档 §2.2）：
 * ARTICLE("article") → article_idx（文章全文/元数据检索）
 * BOOK("book")       → book_idx（书名/作者/简介检索）
 * USER("user")       → user_idx（笔名/简介检索）
 *
 * code 与 REST 参数 /search?type= 取值一致（小写），
 * 实现 DictEnum：接入 common-core 三值兼容绑定（name/code/desc 均可作为请求参数）
 */
@Getter
public enum SearchTypeEnum implements DictEnum {

    /** 文章（默认检索对象，路由 article_idx） */
    ARTICLE("article", "文章"),

    /** 书籍（路由 book_idx） */
    BOOK("book", "书籍"),

    /** 用户（路由 user_idx） */
    USER("user", "用户");

    /** REST 参数值（如 ?type=article），同时作为返回给前端的类型标识 */
    @JsonValue
    private final String code;

    /** 前端展示用中文描述 */
    private final String desc;

    SearchTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
