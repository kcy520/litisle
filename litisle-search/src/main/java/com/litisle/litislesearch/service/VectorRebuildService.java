package com.litisle.litislesearch.service;

/**
 * 向量全量重建服务（批次七）：模型接入/切换后重算 article_idx.vec_dense 与 article_chunks.chunk_vector。
 *
 * 【背景】向量模型语义漂移（换模型/换维度）必须全量重建，架构文档 §八-8；
 * 重建按 search_after 分页扫描，逐条向量化后 bulk partial update，只改向量字段不动其余字段。
 * 【前置】必须先满足 {@link VectorService#available()}（开关打开且提供方已接入），否则整体跳过并告警。
 * 【触发方式】批次七仅落地重建框架；手动管理端点/一次性任务/开关触发方式待定，
 * 由后续调用方（运维端点或启动任务）直接调用本服务方法。
 *
 * @author litisle
 */
public interface VectorRebuildService {

    /**
     * 重建 article_idx 全部公开文章的 vec_dense。
     *
     * @return 成功更新条数；提供方未就绪时返回 0（不执行任何写操作）
     */
    int rebuildArticleVectors();

    /**
     * 重建 article_chunks 全部切片的 chunk_vector。
     *
     * @return 成功更新条数；提供方未就绪时返回 0（不执行任何写操作）
     */
    int rebuildChunkVectors();
}
