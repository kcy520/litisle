package com.litisle.litislesearch.service;

import com.litisle.litisleapi.vo.search.SimilarArticleVO;

import java.util.List;

/**
 * 相似文章推荐服务（批次七 7.1）。
 *
 * 【职责】以源文章的 vec_dense 做 KNN 检索，融合标签重合度与行为热度后返回相似文章列表；
 * REST（GET /api/search/similar/{articleId}）与 Feign（GET /api/feign/search/similar）共用。
 *
 * @author litisle
 */
public interface SimilarService {

    /**
     * 查询相似文章。
     *
     * <p>铁律（批次C C3）：KNN 检索强制 filter visibility=PUBLIC，私密/草稿/下架内容永不出现。</p>
     *
     * @param articleId 源文章 ID（必须已入 article_idx 且存在向量）
     * @param topK      返回条数（可空；缺省取 knn.top-k，超过 knn.max-top-k 截断）
     * @return 相似文章列表（按融合分降序）；源文章无向量时返回空列表
     */
    List<SimilarArticleVO> similar(Long articleId, Integer topK);
}
