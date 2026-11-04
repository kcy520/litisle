package com.litisle.litislesearch.service;

import java.util.List;

/**
 * 向量化服务（Embedding 统一入口）。
 *
 * 【职责】把文本转为 1024 维向量，供 article_idx 的 vec_dense 与 article_chunks 的 chunk_vector 写入，
 * 以及批次七相似推荐/批次四空结果兜底的查询词实时向量化。
 * 【当前状态（批次七）】已落地 Embedding 提供方 SPI（embedding/EmbeddingProvider）与全部外置配置；
 * 具体提供方适配器待选型确认后实现，未接入时 {@link #embed(String)} 返回 null，
 * 调用方必须跳过向量字段写入（不得把 null 写入 dense_vector）。
 *
 * @author litisle
 */
public interface VectorService {

    /**
     * 判断向量化能力是否就绪（开关打开 + 配置的提供方已注册且本地配置完整）。
     *
     * <p>全量重建任务与相似检索据此决定是执行还是跳过，避免无提供方时逐条空跑。</p>
     *
     * @return true=可以向量化
     */
    boolean available();

    /**
     * 文本转向量。
     *
     * @param text 待向量化文本
     * @return 1024 维向量；模型提供方未接入、文本为空或调用失败时返回 null
     */
    List<Float> embed(String text);
}
