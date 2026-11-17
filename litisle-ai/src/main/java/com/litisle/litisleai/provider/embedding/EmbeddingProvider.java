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
 * <p>【通俗说明】把文字变成"一串数字"的标准通道（文本向量化）。向量就像这段文字在
 * 语义空间里的坐标——意思相近的两段话，坐标也挨得近，AI 靠这个判断"哪段内容和
 * 用户问题最像"。本接口只定义这一件事；具体用哪家模型（当前是本地 Ollama + bge-m3）
 * 由实现类决定，换供应商不影响上层代码。</p>
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
     *
     * <p>【通俗说明】进一段文字，出一段数字（向量）。失败直接抛异常，绝不返回
     * 空向量充数——空坐标会把检索结果带偏。</p>
     */
    List<Float> embed(String text);
}
