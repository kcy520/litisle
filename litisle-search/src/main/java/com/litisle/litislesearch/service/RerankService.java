package com.litisle.litislesearch.service;

import com.litisle.litisleapi.vo.search.SimilarArticleVO;

import java.util.List;

/**
 * 本地 reranker 精排服务（search 板块三 3.2 升级②）：条件版相似查询的按需升级中间档。
 *
 * 【职责边界】本服务承担重排的触发判定、候选文本组装与排序编排；
 * 打分本身由 {@link com.litisle.litislesearch.rerank.RerankProvider} 承担。
 * 重排为 0 次 LLM 的本地推理（bge-reranker / cross-encoder），零 API 费。
 *
 * 【护栏铁律】每次升级必须有明确触发条件 + 配置开关 + 降级路径：
 * 未启用 / queryText 为空 / 提供方未就绪 / 调用失败一律 fail-open 退回融合基线排序，结果不劣化。
 *
 * @author litisle
 */
public interface RerankService {

    /**
     * 重排能力是否就绪（开关打开且配置的提供方已注册并可用）。
     *
     * @return true=可调用 rerank 精排
     */
    boolean enabled();

    /**
     * 对候选文章按 (queryText, 候选摘要) 相关性重排（0 次 LLM）。
     *
     * @param queryText  查询文本（空则不重排原样返回）
     * @param candidates 候选文章列表（融合基线排序，可空）
     * @param topK       最终返回条数
     * @return 重排后的文章列表（score 更新为相关性分）；未启用/失败退回基线列表截断 topK
     */
    List<SimilarArticleVO> rerank(String queryText, List<SimilarArticleVO> candidates, int topK);
}
