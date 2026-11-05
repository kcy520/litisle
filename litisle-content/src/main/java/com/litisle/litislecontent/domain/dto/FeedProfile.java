package com.litisle.litislecontent.domain.dto;

import com.litisle.litislecontent.enums.ArticleCategoryEnum;
import com.litisle.litislecontent.enums.BookStatusEnum;
import com.litisle.litislecontent.enums.ZoneEnum;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Collections;
import java.util.Set;

/**
 * 用户推荐画像（批次十 10.3：P0 只用「分区 + 分类」两个维度，且只从 reading_history 聚合）
 *
 * <p>为什么单独建对象：文章流四路召回与书籍流排序都要读同一份画像，
 * 收口成一个对象可避免两个流各自查库、口径不一致。10.5 会在此基础上扩展缓存与收藏维度。</p>
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
}
