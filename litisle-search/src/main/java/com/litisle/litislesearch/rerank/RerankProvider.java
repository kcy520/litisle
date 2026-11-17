package com.litisle.litislesearch.rerank;

import java.util.List;

/**
 * rerank 提供方 SPI（search 板块三 3.2 升级②）：外部重排模型的统一接入扩展点。
 *
 * 【职责边界】
 * 1. 本 SPI 只做一件事：对 (queryText, 候选文档) 逐对打相关性分，返回与输入下标一一对应的分数列表；
 *    排序、截断、降级决策全部由 RerankService 承担，实现类不做排序；
 * 2. 模型/服务地址/密钥全部外置配置（litisle.search.rerank.*），实现类不得硬编码；
 * 3. {@link #providerName()} 与配置 rerank.provider 精确匹配，RerankService 按配置挑选实现；
 * 4. {@link #available()} 只做「配置是否就绪」的本地判断（base-url、model 非空），
 *    不发起网络探测，避免查询链路被远程调用拖慢。
 *
 * 【失败语义】任何网络/解析异常返回 null（整批失败），由 RerankService 退回融合基线排序，结果不劣化。
 *
 * @author litisle
 */
public interface RerankProvider {

    /**
     * 提供方类型标识（与配置 litisle.search.rerank.provider 精确匹配）。
     *
     * @return 提供方标识，如 openai-compatible
     */
    String providerName();

    /**
     * 模型标识（日志登记用）。
     *
     * @return 模型名（如 bge-reranker-v2-m3）
     */
    String modelName();

    /**
     * 提供方是否可用（仅本地配置判断，不发起网络请求）。
     *
     * @return true=可用，允许 RerankService 调用
     */
    boolean available();

    /**
     * 对 (queryText, 候选文档) 逐对打相关性分。
     *
     * @param queryText 查询文本（调用方保证非空）
     * @param documents 候选文档列表（调用方保证非空且已截断）
     * @return 与 documents 等长的相关性分数列表（下标一一对应；单个候选打分失败该项为 null）；
     *         整批失败（网络/解析/非 200）返回 null——实现类不得抛出异常
     */
    List<Double> rerank(String queryText, List<String> documents);
}
