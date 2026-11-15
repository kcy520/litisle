package com.litisle.litisleai.provider.embedding;

import java.util.List;

/**
 * 文本向量通道端口抽象（板块四 4.1：P3 定档 Ollama bge-m3 本地向量通道的唯一出口）
 *
 * <p>【职责边界】provider 是唯一接触向量模型 SDK/HTTP 的层（与文本模型通道 TextModelProvider
 * 同构）；摄入链路与检索链路只依赖本端口，不直连 Ollama；换向量供应商只改实现类与 yml。</p>
 *
 * <p>【失败语义】调用失败 fail-fast 上抛（RagIngestService 侧转为消费重试；
 * RagSearchService 侧转为降级无引用回答），不静默吞错、不返回空向量占位。</p>
 *
 * @author litisle
 */
public interface EmbeddingProvider {

    /**
     * 生成文本向量
     *
     * @param text 待向量化的文本（切片正文 / 用户查询）
     * @return 向量（维度与配置 vectorDimension 一致）
     * @throws com.litisle.commoncore.exception.BizException 通道未启用（80005）或调用失败
     */
    List<Float> embed(String text);
}
