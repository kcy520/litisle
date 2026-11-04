package com.litisle.litislesearch.controller.feign;

import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.search.SearchRequestDTO;
import com.litisle.litisleapi.vo.search.SearchResultVO;
import com.litisle.litisleapi.vo.search.SimilarArticleVO;
import com.litisle.litislesearch.service.SearchService;
import com.litisle.litislesearch.service.SimilarService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 搜索服务 Feign 提供方（批次四 4.1 + 批次七 7.2，实现 litisle-api 的 SearchClient 契约）。
 *
 * 【路径】（/api/feign 前缀仅服务间可用，网关未注册路由）：
 * - GET  /api/feign/search/similar：KNN 相似推荐（SearchClient#similar，fail-fast）
 * - POST /api/feign/search/search：服务间检索（SearchClient#search，非降级链路）
 * 【说明】同一套参数校验/缓存/兜底/相似推荐逻辑由 {@link SearchService}/{@link SimilarService} 收口，
 * 本控制器只负责把 Feign 契约请求转成服务调用，保持 REST 与 Feign 行为一致。
 *
 * @author litisle
 */
@RestController
@RequestMapping("/api/feign/search")
@RequiredArgsConstructor
public class SearchFeignProviderController {

    /** 检索服务（REST 与 Feign 共用同一业务入口） */
    private final SearchService searchService;

    /** 相似推荐服务（批次七 7.2：SearchClient#similar 契约实现） */
    private final SimilarService similarService;

    /**
     * 服务间相似推荐（SearchClient#similar 契约实现）。
     *
     * @param articleId 源文章 ID
     * @param topK      返回条数（可选，缺省由提供方配置默认值）
     * @return 相似文章列表（按融合分降序）
     */
    @GetMapping("/similar")
    public Result<List<SimilarArticleVO>> similar(
            @RequestParam("articleId") Long articleId,
            @RequestParam(value = "topK", required = false) Integer topK) {
        return Result.success(similarService.similar(articleId, topK));
    }

    /**
     * 服务间检索（SearchClient#search 契约实现）。
     *
     * @param request 检索请求体（关键词 + 类型/排序/分页/筛选）
     * @return 检索结果（source 标记来源，非 ES 来源调用方不得缓存）
     */
    @PostMapping("/search")
    public Result<SearchResultVO> search(@RequestBody SearchRequestDTO request) {
        return Result.success(searchService.search(request));
    }
}
