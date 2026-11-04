package com.litisle.litislesearch.service;

import com.litisle.litisleapi.dto.search.SearchRequestDTO;
import com.litisle.litisleapi.vo.search.SearchResultVO;

/**
 * 站内检索服务（批次四 4.1 / 4.2）。
 *
 * 【职责】检索接口的唯一业务入口（REST GET /search 与 Feign POST /api/feign/search/search 共用）：
 * 参数校验 → 热点缓存 → type 路由索引查询（bool + function_score）→ 排序/高亮/分页
 * → 空结果兜底（实时 Embedding + KNN，失败再退 HEAT 热门列表）。
 *
 * @author litisle
 */
public interface SearchService {

    /**
     * 执行检索。
     *
     * @param request 检索请求（q 必填；type/sort/分页/筛选可选）
     * @return 检索结果（按 type 仅对应命中列表非空；source 标记结果来源）
     */
    SearchResultVO search(SearchRequestDTO request);
}
