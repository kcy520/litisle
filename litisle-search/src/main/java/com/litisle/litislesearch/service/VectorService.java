package com.litisle.litislesearch.service;

import java.util.List;

/**
 * 向量化服务（Embedding 统一入口）。
 *
 * 【职责】把文本转为 1024 维向量，供 article_idx 的 vec_dense 与 article_chunks 的 chunk_vector 写入，
 * 以及批次七相似推荐/批次四空结果兜底的查询词实时向量化。
 * 【当前状态（批次二）】向量模型提供方尚未选型接入（批次C C1 仅定维度），
 * 实现返回 null 表示「无法向量化」，调用方必须跳过向量字段写入（不得把 null 写入 dense_vector）。
 *
 * @author litisle
 */
public interface VectorService {

    /**
     * 文本转向量。
     *
     * @param text 待向量化文本
     * @return 1024 维向量；模型提供方未接入或文本为空时返回 null
     */
    List<Float> embed(String text);
}
