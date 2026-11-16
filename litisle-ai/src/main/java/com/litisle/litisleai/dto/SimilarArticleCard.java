package com.litisle.litisleai.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 相似文章推荐卡片（板块五 5.1：F8 推荐列表元素）
 *
 * <p>【口径】只含已发布且公开的文章（回查不通过即移除，下架即失效）；
 * reason 为 5.2 可选增强产物，开关关闭或生成失败时为 null。</p>
 *
 * @author litisle
 */
@Data
public class SimilarArticleCard implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 文章 ID */
    private Long articleId;

    /** 文章标题（content 当前值） */
    private String title;

    /** 作者 ID */
    private Long authorId;

    /** 作者昵称（用户服务不可用可为空，仅展示用） */
    private String authorName;

    /** 相似度得分（knn cosine score，该篇最高分切片） */
    private double similarity;

    /** 内容已更新标注（摄入时 contentHash 与当前不一致） */
    private boolean outdated;

    /** 一句话推荐理由（5.2 可选增强，默认关闭；生成失败时为 null） */
    private String reason;
}
