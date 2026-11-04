package com.litisle.litislesearch.service;

import com.litisle.litislesearch.domain.entity.ArticleIndexDoc;

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
     * 重建文章切片（先删旧块再写新块，保证按 articleId 幂等）。
     *
     * @param doc       文章索引文档（提供标题/作者/分区/可见性/发布时间等冗余字段）
     * @param plainText 文章正文纯文本（已去 HTML）
     */
    void rebuild(ArticleIndexDoc doc, String plainText);

    /**
     * 删除文章全部切片（文章下架/转私密/删除时调用，404/索引不存在视为成功）。
     *
     * @param articleId 文章 ID
     */
    void deleteByArticleId(Long articleId);
}
