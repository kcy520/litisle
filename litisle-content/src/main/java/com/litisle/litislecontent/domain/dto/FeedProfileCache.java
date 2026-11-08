package com.litisle.litislecontent.domain.dto;

import com.litisle.litislecontent.enums.BookStatusEnum;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Collections;
import java.util.Set;

/**
 * 推荐画像缓存载体（批次十 10.5：Redis 缓存专用结构）
 *
 * <p>为什么不直接缓存 {@link FeedProfile}：画像里的分区/分类/书籍状态都是枚举，
 * 枚举只标了 @JsonValue（序列化成中文展示文案），Redis 反序列化依赖 Jackson 对 @JsonValue
 * 的反向解析；一旦枚举展示文案调整，旧缓存就可能解析失败。本载体只使用 int/string/基本集合，
 * 缓存格式与展示文案、枚举常量名彻底解耦，旧缓存可稳定还原。</p>
 *
 * <p>字段与 {@link FeedProfile} 一一对应，只做「枚举转码/名称」的薄转换。</p>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class FeedProfileCache implements Serializable {

    private static final long serialVersionUID = 1L;

//    FeedProfileCache和FeedProfile  为什么要区分？字段不是一样么？
//    可以看到，三个枚举字段全部被转成了 int/string，只有纯基本类型/集合字段保持原样   枚举序列化走的是“展示文案”，不是“常量名”

    /** 是否冷启动（近期无文章阅读行为） */
    private boolean coldStart = true;

    /** 已读文章 ID 集合（仅用于建池打分降权，不剔除） */
    private Set<Long> readArticleIds = Collections.emptySet();

    /** 常看分区码集合（ZoneEnum#getCode：1-普通 / 2-作家） */
    private Set<Integer> preferredZoneCodes = Collections.emptySet();

    /** 常看分类名集合（ArticleCategoryEnum#getName：中文分类名） */
    private Set<String> preferredCategoryNames = Collections.emptySet();

    /** 偏好书籍状态码（BookStatusEnum#getCode：0-连载中 / 1-已完结） */
    private int preferredBookStatusCode = BookStatusEnum.COMPLETED.getCode();

    /** 标签偏好（P1 预留，本期恒为空集合；不读取 article_tag） */
    private Set<String> preferredTags = Collections.emptySet();
}
