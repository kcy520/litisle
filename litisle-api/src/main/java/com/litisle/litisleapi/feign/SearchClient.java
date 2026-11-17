package com.litisle.litisleapi.feign;

import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.config.FeignApiConfig;
import com.litisle.litisleapi.dto.search.SearchRequestDTO;
import com.litisle.litisleapi.dto.search.SimilarConditionRequestDTO;
import com.litisle.litisleapi.fallback.SearchClientFallbackFactory;
import com.litisle.litisleapi.vo.search.SearchResultVO;
import com.litisle.litisleapi.vo.search.SimilarArticleVO;
import com.litisle.litisleapi.vo.search.UserSearchVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 搜索服务 Feign 客户端（跨服务契约）
 *
 * 【设计说明】
 * 1. 契约先行：本接口与 DTO/VO、降级工厂已就绪；提供方端点待 search 服务实现
 *    TODO litisle-search 实现 /api/feign/search/**（KNN 相似推荐 + 服务间检索）
 * 2. 路径前缀 /api/feign/search 遵循规范：网关未注册该前缀路由，仅服务间可用
 *    （前端检索走 REST 端点 GET /search，不经本契约）
 * 3. 相似推荐是 ai 服务 RAG 核心读依赖，降级 fail-fast（见 SearchClientFallbackFactory）：
 *    静默降级返回空列表会让 RAG 误判「无相关素材」，异常穿透由调用方决策
 *
 * 【调用方】ai 模块（RAG 相似推荐 / 条件化相似推荐 / 作者名解析）、业务方服务间检索（非降级链路）
 *
 * @author litisle
 */
@FeignClient(
        name = "litisle-search",
        configuration = FeignApiConfig.class,
        fallbackFactory = SearchClientFallbackFactory.class
)
public interface SearchClient {

    /**
     * 相似文章推荐（KNN 向量检索 + 多因子加权）
     *
     * <p>【设计说明】
     * 1. 契约先行：提供方端点待 search 服务实现 GET /api/feign/search/similar；
     * 2. 批次C C3 定案：KNN 检索强制 filter visibility=PUBLIC，私密/草稿/下架文章不出现在结果中；
     * 3. topK 可选：调用方不传时由提供方取默认值，契约层不做上限校验（提供方收口）</p>
     *
     * @param articleId 源文章 ID（以该文章向量/标签作 KNN 输入）
     * @param topK      返回条数上限（可选，缺省由提供方默认）
     * @return 相似文章列表（按融合分降序）；源文章不存在/无向量时提供方返回 Result.fail 或空列表
     */
    @GetMapping("/api/feign/search/similar")
    Result<List<SimilarArticleVO>> similar(@RequestParam("articleId") Long articleId,
                                           @RequestParam(value = "topK", required = false) Integer topK);

    /**
     * 服务间检索（非降级链路；ES 不可用降级方案见批次A A3：返回热门列表，不走本契约查 DB）
     *
     * <p>【设计说明】
     * 1. 契约先行：提供方端点待 search 服务实现 POST /api/feign/search/search；
     * 2. 用 POST + body 承载多字段筛选条件（GET query 传参在此场景冗长且易超长）；
     * 3. 参数校验与分页上限由提供方收口（SEARCH_PARAM_INVALID / SEARCH_PAGE_EXCEEDED）</p>
     *
     * @param dto 检索请求（关键词 + 类型/排序/分页/筛选）
     * @return 检索结果（按 type 仅对应命中列表非空；source 标记来源，非 ES 来源不得缓存）
     */
    @PostMapping("/api/feign/search/search")
    Result<SearchResultVO> search(@RequestBody SearchRequestDTO dto);

    /**
     * 条件版相似文章检索（板块三 3.1：四类维度 match 组合查询，ai 条件提取管道消费）
     *
     * <p>【设计说明】
     * 1. visibility=PUBLIC + 排除自身铁律由提供方强制（与 similar 同口径）；
     * 2. queryText 非空 → embed 后 knn 作参照向量；为空 → 退化纯条件过滤检索（按 sortHint 排序）；
     * 3. aiLabels 维度按提供方维度注册表路由：注册表外 key 忽略（防未注册维度污染查询）；
     * 4. 无命中返回空列表不报错</p>
     *
     * @param dto 条件检索请求（各条件均可空，至少一项有效）
     * @return 相似文章列表（按 sortHint 对应排序；无命中为空列表）
     */
    @PostMapping("/api/feign/search/similar/conditional")
    Result<List<SimilarArticleVO>> similarByCondition(@RequestBody SimilarConditionRequestDTO dto);

    /**
     * 用户笔名查询（板块八 1.2：作者名 → 作者 ID 解析，普通索引查询非 LLM）
     *
     * <p>【设计说明】按笔名模糊命中 user_idx 公开资料最小集；
     * 解析命中取舍由调用方决策（优先精确匹配，次取最高相关）。</p>
     *
     * @param keyword 笔名关键词（必传）
     * @param limit   返回条数上限（可空，提供方默认并封顶）
     * @return 命中用户列表（userId + penName）；无命中为空列表
     */
    @GetMapping("/api/feign/search/users")
    Result<List<UserSearchVO>> searchUsers(@RequestParam("keyword") String keyword,
                                           @RequestParam(value = "limit", required = false) Integer limit);

    /**
     * 回查文章 AI 标签（板块十二 4.6：风格仿写把 ai_labels 风格标签从「筛选用」变「生成用」）
     *
     * <p>【设计说明】读 article_idx 单篇 ai_labels 词表型维度映射（style/depth 等）；
     * 文章未入索引或未打标返回空 map（调用方按「无风格标签」降级为仅参考正文笔法）；
     * ES 异常 fail-fast 由降级工厂穿透（静默空 map 会让仿写误判「无标签可约束」）。</p>
     *
     * @param articleId 文章 ID
     * @return ai_labels 维度映射（key=维度名如 style，value=词表值）；未入索引/未打标为空 map
     */
    @GetMapping("/api/feign/search/article-labels")
    Result<java.util.Map<String, String>> getArticleAiLabels(@RequestParam("articleId") Long articleId);
}
