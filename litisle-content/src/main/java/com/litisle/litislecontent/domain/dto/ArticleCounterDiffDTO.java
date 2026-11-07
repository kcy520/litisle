package com.litisle.litislecontent.domain.dto;

import lombok.Data;

/**
 * 文章计数对账差异结果（批次十一 11.16）
 *
 * <p>作用：承载一次聚合查询中「article 主表计数」与「article_stat 统计表计数」不一致的文章行数，
 * 三项分别对应浏览量、点赞数、评论数，供对账任务打日志与指标。</p>
 *
 * @author litisle
 */
@Data
public class ArticleCounterDiffDTO {

    /** 浏览量不一致的文章行数（article.view_cnt vs article_stat.view_count） */
    private Long viewDiff;

    /** 点赞数不一致的文章行数（article.like_cnt vs article_stat.like_count） */
    private Long likeDiff;

    /** 评论数不一致的文章行数（article.comment_cnt vs article_stat.comment_count） */
    private Long commentDiff;
}
