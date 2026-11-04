package com.litisle.litislesearch.embedding;

import java.util.ArrayList;
import java.util.List;

/**
 * Embedding 提供方 SPI（批次七）：外部向量模型的统一接入扩展点。
 *
 * 【设计说明】
 * 1. 模型/服务地址/密钥全部外置配置（litisle.search.embedding.*），实现类不得硬编码；
 * 2. {@link #providerName()} 与配置 embedding.provider 精确匹配，VectorService 按配置挑选实现；
 * 3. {@link #available()} 只做「配置是否就绪」的本地判断（如 enabled、base-url、密钥非空），
 *    不得发起网络探测，避免索引写入链路被远程调用拖慢；
 * 4. 提供方实现接入后，必须按架构文档 §八-8 全量重建 vec_dense / chunk_vector（模型语义漂移）。
 *
 * 【当前状态】批次七仅落地 SPI 与配置骨架，具体提供方适配器待选型确认后再实现。
 *
 * @author litisle
 */
public interface EmbeddingProvider {

    /**
     * 提供方类型标识（与配置 litisle.search.embedding.provider 精确匹配）。
     *
     * @return 提供方标识，如 openai-compatible / ollama / dashscope / tei
     */
    String providerName();

    /**
     * 模型标识（日志与索引 _meta 登记用）。
     *
     * @return 模型名（如 bge-m3 / text-embedding-v3）
     */
    String modelName();

    /**
     * 提供方是否可用（仅本地配置判断，不发起网络请求）。
     *
     * @return true=可用，允许 VectorService 调用
     */
    boolean available();

    /**
     * 单条文本向量化。
     *
     * @param text 待向量化文本（调用方保证非空）
     * @return 固定维度向量；调用失败返回 null（实现类不得抛出异常，由 VectorService 统一吞掉，避免阻断索引写入）
     */
    List<Float> embed(String text);

    /**
     * 批量文本向量化：默认逐条调用，提供方有批量接口时覆盖以降低网络往返。
     *
     * @param texts 待向量化文本列表
     * @return 与输入等长的向量列表，失败项为 null
     */
    default List<List<Float>> embedBatch(List<String> texts) {
        // 默认实现：逐条向量化，保持与单条接口一致的失败语义（失败项返回 null）
        List<List<Float>> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embed(text));
        }
        return vectors;
    }
}
