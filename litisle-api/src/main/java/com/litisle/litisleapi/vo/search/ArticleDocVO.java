package com.litisle.litisleapi.vo.search;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 文章索引命中 VO（article_idx 文档投影，跨服务检索契约）
 *
 * 【使用场景】
 * - SearchResultVO.articleList 元素（服务间检索返回）
 * - SimilarArticleVO 与本 VO 分开定义：相似推荐只回传轻量字段，避免契约膨胀
 *
 * 【设计要点】
 * - tags 用 List<String>：与 ES article_idx.tags keyword 数组对齐，避免提供方拼接拆分
 * - highlight 为命中的高亮片段（可能为空，未命中高亮时提供方不填充）
 * - heat 用 BigDecimal：热度公式为加权浮点（批次A A5 定案），序列化保留精度由提供方控制
 *
 * @author litisle
 */
@Data
public class ArticleDocVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 文章 ID */
    private Long articleId;

    /** 标题 */
    private String title;

    /** 摘要 */
    private String summary;

    /** 标签列表（与索引 tags 字段同源） */
    private java.util.List<String> tags;

    /** 作者用户 ID */
    private Long authorId;

    /** 作者笔名（article_idx.authorName 投影，含 completion 子字段供联想） */
    private String authorName;

    /** 热度分（加权公式见 Search-Architecture.md §四，批次A A5 定案） */
    private BigDecimal heat;

    /** 最后更新时间（索引 updateTime 投影） */
    private LocalDateTime updateTime;

    /** 命中字段高亮片段（未命中高亮时为空） */
    private String highlight;
}
