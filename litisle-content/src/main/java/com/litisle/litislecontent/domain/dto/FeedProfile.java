package com.litisle.litislecontent.domain.dto;

import com.litisle.litislecontent.enums.ArticleCategoryEnum;
import com.litisle.litislecontent.enums.BookStatusEnum;
import com.litisle.litislecontent.enums.ZoneEnum;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Collections;
import java.util.Set;

/**
 * 用户推荐画像（批次十 10.3/10.5：P0 只用「分区 + 分类」两个维度，且只从 reading_history 聚合）
 *
 * <p>为什么单独建对象：文章流四路召回与书籍流排序都要读同一份画像，
 * 收口成一个对象可避免两个流各自查库、口径不一致。</p>
 *
 * <p>缓存口径（10.5）：本对象不直接序列化进 Redis（枚举只标了 @JsonValue，反序列化依赖展示文案），
 * 由 {@link com.litisle.litislecontent.service.support.FeedProfileSupport} 转成 {@link FeedProfileCache}
 * 载体后缓存；登录按 userId、匿名按 anonId 各存一份，不混用。</p>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class FeedProfile {

    /** 是否冷启动：无任何阅读行为时为 true，两路个性化召回应退化为全局热门/最新 */
    private boolean coldStart = true;

    /** 已读文章 ID 集合（reading_history 近 readWindowDays 天的独立文章，用于打分降权，不剔除） */
    private Set<Long> readArticleIds = Collections.emptySet();

    /** 常看分区集合（按阅读次数取最高频的一个；空集合表示无偏好） */
    private Set<ZoneEnum> preferredZones = Collections.emptySet();

    /** 常看分类集合（按阅读次数取前二；空集合表示无偏好） */
    private Set<ArticleCategoryEnum> preferredCategories = Collections.emptySet();

    /** 偏好书籍状态（按已读书籍「完结占比」判断；无已读书籍时默认偏好已完结） */
    private BookStatusEnum preferredBookStatus = BookStatusEnum.COMPLETED;

    /**
     * 标签偏好（P1 预留，本期不实现）
     *
     * <p>P0 口径不读取 article_tag（与 content-schema.sql 注释一致）；保留该字段只为 P1 接入时
     * 不改调用方签名。本期恒为空集合，任何排序逻辑都不得依赖它。</p>
     */
    private Set<String> preferredTags = Collections.emptySet();
}
