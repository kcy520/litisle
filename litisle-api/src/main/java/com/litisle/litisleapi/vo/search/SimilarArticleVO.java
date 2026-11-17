package com.litisle.litisleapi.vo.search;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 相似文章推荐 VO（KNN 相似推荐跨服务契约）
 *
 * 【使用场景】
 * - ai 服务 RAG / 相似推荐复用（SearchClient.similar(articleId, topK)）
 * - 前端「相关文章」经 REST 端点复用同一 VO
 *
 * 【设计要点】
 * - 轻量字段：只回传展示所需最小集，正文/标签等重字段由调用方按 articleId 回查，防契约膨胀
 * - score 为 KNN 相似度与标签重合、行为加权的融合分（批次C C3 定案：KNN 强制 PUBLIC 过滤）
 *
 * @author litisle
 */
@Data
public class SimilarArticleVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 相似文章 ID */
    private Long articleId;

    /** 作者 ID（板块三 3.1 补充：条件版检索调用方组装作者维度展示用） */
    private Long authorId;

    /** 标题 */
    private String title;

    /** 摘要 */
    private String summary;

    /** 作者笔名 */
    private String authorName;

    /** 相似度融合分（KNN 向量相似 + 标签重合 + 行为加权，越高越相似） */
    private Double score;

    /** 互动热度（板块三 3.1 补充：条件版 sortHint=heat 排序依据透传） */
    private Double heat;

    /** 更新时间（板块三 3.1 补充：条件版 sortHint=newest 排序依据透传，ISO-8601） */
    private LocalDateTime updateTime;
}
