package com.litisle.litislesearch.service;

import com.litisle.litislesearch.domain.entity.ArticleIndexDoc;

import java.util.List;

/**
 * RAG 知识库切片服务（article_chunks 摄入维护，架构文档 §四）。
 *
 * 【职责】文章发布/更新时按 articleId 幂等覆盖切片（删旧块写新块）；文章下架时删除该文章全部切片。
 * 【门控】仅公开过审文章入库；正文长度低于门槛（litisle.search.ingest.min-content-length）时不入库。
 *
 * @author litisle
 */
public interface ArticleChunkService {

    /**
     * 重建文章切片（先删旧块再写新块，保证按 articleId 幂等），并返回本批切片向量。
     *
     * <p>search 板块一 1.3 起返回切片向量供池化聚合 vec_dense（保证切片向量在位后再池化）；
     * 切片向量与切片文档同批写入，调用方无需重复回查。</p>
     *
     * @param doc       文章索引文档（提供标题/作者/分区/可见性/发布时间等冗余字段）
     * @param plainText 文章正文纯文本（已去 HTML）
     * @return 本批切片向量列表（按切片顺序，剔除单片 embed 失败的 null 元素）；
     *         正文过短未入库或 Embedding 未产出时返回空列表（调用方走无切片兜底）
     */
    List<List<Float>> rebuild(ArticleIndexDoc doc, String plainText);

    /**
     * 删除文章全部切片（文章下架/转私密/删除时调用，404/索引不存在视为成功）。
     *
     * @param articleId 文章 ID
     */
    void deleteByArticleId(Long articleId);
}
