package com.litisle.litislesearch.service;

/**
 * 文章索引同步服务（批次二）：article_idx 与 article_chunks 的写入编排。
 *
 * 【职责】把 content 侧文章事件转换为 ES 文档写入动作：
 * - 发布：回查元数据 → 门控 → upsert 文章索引文档 + 重建 RAG 切片
 * - 下架：删除文章索引文档 + 删除全部切片
 * - 元数据编辑：只更新标题/标签/摘要等索引字段（不动正文、向量与切片）
 *
 * 【幂等】三类动作都以 articleId 为幂等键（upsert / delete / partial update 重复执行结果一致），
 * 因此不依赖去重键即可安全重试。
 *
 * @author litisle
 */
public interface ArticleIndexService {

    /**
     * 处理文章发布（content.published）。
     *
     * @param articleId 文章 ID（事件体定位键）
     */
    void handlePublished(Long articleId);

    /**
     * 处理文章下架（content.unpublished，覆盖删除/转私密/驳回/封禁连带四条路径）。
     *
     * @param articleId 文章 ID
     */
    void handleUnpublished(Long articleId);

    /**
     * 处理已发布文章元数据编辑（content.article-updated）。
     *
     * @param articleId 文章 ID
     */
    void handleArticleUpdated(Long articleId);
}
