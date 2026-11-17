package com.litisle.litislesearch.controller.feign;

import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.search.SearchRequestDTO;
import com.litisle.litisleapi.dto.search.SimilarConditionRequestDTO;
import com.litisle.litisleapi.vo.search.SearchResultVO;
import com.litisle.litisleapi.vo.search.SimilarArticleVO;
import com.litisle.litisleapi.vo.search.UserSearchVO;
import com.litisle.litislesearch.service.SearchService;
import com.litisle.litislesearch.service.SimilarService;
import com.litisle.litislesearch.service.UserIndexService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 搜索服务 Feign 提供方（批次四 4.1 + 批次七 7.2 + 板块三 3.1 / 板块八 1.2，
 * 实现 litisle-api 的 SearchClient 契约）。
 *
 * 【路径】（/api/feign 前缀仅服务间可用，网关未注册路由）：
 * - GET  /api/feign/search/similar：KNN 相似推荐（SearchClient#similar，fail-fast）
 * - POST /api/feign/search/similar/conditional：条件版相似查询（SearchClient#similarByCondition，
 *   板块三 3.1 四类维度 match 组合查询，ai 条件提取管道消费）
 * - GET  /api/feign/search/users：用户笔名查询（SearchClient#searchUsers，板块八 1.2 作者名解析）
 * - POST /api/feign/search/search：服务间检索（SearchClient#search，非降级链路）
 * 【说明】同一套参数校验/缓存/兜底/相似推荐逻辑由 {@link SearchService}/{@link SimilarService}
 * 收口，本控制器只负责把 Feign 契约请求转成服务调用，保持 REST 与 Feign 行为一致。
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

    /** 用户索引服务（板块八 1.2：SearchClient#searchUsers 契约实现，笔名查询） */
    private final UserIndexService userIndexService;

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
     * 回查文章 AI 标签（SearchClient#getArticleAiLabels 契约实现，板块十二 4.6 风格仿写约束）。
     *
     * @param articleId 文章 ID
     * @return ai_labels 维度映射；未入索引/未打标为空 map
     */
    @GetMapping("/article-labels")
    public Result<java.util.Map<String, String>> getArticleAiLabels(@RequestParam("articleId") Long articleId) {
        return Result.success(similarService.getArticleAiLabels(articleId));
    }

    /**
     * 条件版相似查询（SearchClient#similarByCondition 契约实现，板块三 3.1）。
     *
     * <p>【失败语义】无命中返回空列表不报错；queryText 为空退化纯条件过滤检索；
     * ES 异常按 SEARCH_ES_UNAVAILABLE fail-fast 上抛（调用方 ai 侧降级 L0）。</p>
     *
     * @param request 条件检索请求（各条件均可空）
     * @return 相似文章列表（按 sortHint 对应排序；无命中为空列表）
     */
    @PostMapping("/similar/conditional")
    public Result<List<SimilarArticleVO>> similarByCondition(@RequestBody SimilarConditionRequestDTO request) {
        return Result.success(similarService.similarByCondition(request));
    }

    /**
     * 用户笔名查询（SearchClient#searchUsers 契约实现，板块八 1.2 作者名 → ID 解析）。
     *
     * @param keyword 笔名关键词（必传）
     * @param limit   返回条数上限（可选，缺省并封顶）
     * @return 命中用户最小集（userId + penName）；无命中为空列表
     */
    @GetMapping("/users")
    public Result<List<UserSearchVO>> searchUsers(
            @RequestParam("keyword") String keyword,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return Result.success(userIndexService.searchByPenName(keyword, limit));
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
