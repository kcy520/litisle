package com.litisle.litislesearch.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litisleapi.dto.search.SearchRequestDTO;
import com.litisle.litisleapi.vo.search.SearchResultVO;
import com.litisle.litislesearch.config.SearchProperties;
import com.litisle.litislesearch.constant.SearchQueryConstant;
import com.litisle.litislesearch.domain.entity.ArticleIndexDoc;
import com.litisle.litislesearch.domain.entity.BookIndexDoc;
import com.litisle.litislesearch.domain.entity.UserIndexDoc;
import com.litisle.litislesearch.enums.SearchTypeEnum;
import com.litisle.litislesearch.metrics.SearchMetrics;
import com.litisle.litislesearch.service.HotQueryCacheService;
import com.litisle.litislesearch.service.SearchHistoryService;
import com.litisle.litislesearch.service.SearchService;
import com.litisle.litislesearch.service.support.EsSearchQueryBuilder;
import com.litisle.litislesearch.service.support.SearchFallbackService;
import com.litisle.litislesearch.service.support.SearchQuery;
import com.litisle.litislesearch.service.support.SearchQueryPreparer;
import com.litisle.litislesearch.service.support.SearchResultConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 站内检索服务实现（批次四 4.1 / 4.2）——检索主链路的编排器。
 *
 * 【职责边界】本类只做检索主链路的编排与记账：缓存读写、指标埋点、搜索历史、各组件调度；
 * 具体能力分别委托给同包组件：
 * 参数校验归一化 → {@link SearchQueryPreparer}；
 * ES 查询 DSL 构建 → {@link EsSearchQueryBuilder}；
 * 空结果兜底与 ES 不可用降级 → {@link SearchFallbackService}；
 * ES 响应转 VO → {@link SearchResultConverter}。
 *
 * 【主链路】参数校验 → 热点查询缓存 → 按 type 路由索引 → bool(must multi_match + filter) →
 * RELATED 排序附加 function_score(heat) → NEWEST/HEAT 字段排序 → 高亮 → 按 type 转换 VO。
 * 【兜底链路】主查询无命中 → 查询词实时 Embedding → article_idx KNN（source=fallback）
 * → 失败退 article_idx HEAT 热门列表 → 热门也失败 → 空页；兜底/降级结果一律不写热点缓存。
 * 【安全】查询词仅走结构化 DSL（不使用 query_string），转义由 Preparer 完成；
 * 排序字段只来自 SearchSortEnum 白名单，筛选字段只使用索引固定字段名，不透传用户输入构造 DSL。
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {

    /** ES 客户端（按 type 执行索引检索） */
    private final ElasticsearchClient elasticsearchClient;

    /** search 配置（索引名、降级开关） */
    private final SearchProperties properties;

    /** 参数校验与归一化组件（原始请求 → 只读 SearchQuery） */
    private final SearchQueryPreparer queryPreparer;

    /** ES 查询 DSL 构建组件（查询/排序/高亮/请求骨架） */
    private final EsSearchQueryBuilder queryBuilder;

    /** 兜底与降级组件（空结果兜底链路 + ES 不可用热门降级） */
    private final SearchFallbackService fallbackService;

    /** 结果转换组件（ES 响应 → 检索契约 VO） */
    private final SearchResultConverter resultConverter;

    /** 热点查询缓存（命中直返 + 常规结果写缓存 + 索引变更失效） */
    private final HotQueryCacheService hotQueryCacheService;

    /** 搜索历史服务（批次六 6.2：检索成功后异步记录，仅本人可见） */
    private final SearchHistoryService searchHistoryService;

    /** 观测指标埋点（批次八 8.2：检索路径/ES 异常/降级计数） */
    private final SearchMetrics searchMetrics;

    /**
     * 执行检索（校验 → 缓存 → ES → 空结果兜底 → 写缓存）。
     *
     * @param request 检索请求
     * @return 检索结果
     */
    @Override
    public SearchResultVO search(SearchRequestDTO request) {
        /*
         * 业务逻辑步骤：
         * 1. 参数校验与归一化（委托 Preparer，产出只读 SearchQuery）；
         * 2. 热点查询缓存命中直接返回（仅常规 ES 命中结果会被写入；兜底结果不缓存）；
         * 3. 执行 ES 检索；ES 整体不可用时按 A3 定案降级为 content 热门列表（source=DEGRADED）；
         * 4. 无命中进入兜底链路（委托 FallbackService，source=fallback）；
         * 5. 写热点缓存（write 内部再次校验 source=ES，兜底/降级结果不会写入）；
         * 6. 异步记录搜索历史（仅登录用户；历史记录为辅助能力，失败不影响检索）。
         */

        // 步骤1：参数校验与归一化（q 必填/长度、type/sort 白名单、分页与深分页上限、时间范围）
        SearchQuery query = queryPreparer.prepare(request);

        // 步骤2：热点查询缓存（仅常规 ES 命中结果会被写入；兜底结果不缓存）
        String cacheKey = hotQueryCacheService.buildKey(request, query.getType().getCode(),
                query.getSort().getCode(), query.getPageNum(), query.getPageSize(), query.getKeyword());
        SearchResultVO cached = hotQueryCacheService.read(cacheKey);
        if (cached != null) {
            // 缓存命中同样是有效检索：异步记录历史（未登录自动跳过）；指标按 cache 路径记账
            searchMetrics.recordSearch(query.getType().getCode(), SearchMetrics.OUTCOME_CACHE);
            recordHistory(query);
            return cached;
        }

        // 步骤3：执行 ES 检索；ES 整体不可用时按 A3 定案降级为 content 热门列表（source=DEGRADED）
        SearchResultVO result;
        try {
            result = executeSearch(query);
        } catch (BizException e) {
            // 仅 ES 不可用错误码参与降级；参数校验等业务异常原样抛出
            if (ErrorCode.SEARCH_ES_UNAVAILABLE.getCode() != e.getCode()) {
                throw e;
            }
            searchMetrics.recordEsError(query.getType().getCode());
            // 降级开关关闭：不降级，直接返回服务不可用（批次八 V3 验证口径）
            if (!properties.getFallback().isEnabled()) {
                searchMetrics.recordSearch(query.getType().getCode(), SearchMetrics.OUTCOME_ERROR);
                throw e;
            }
            // 降级结果不写热点缓存（理由见 FallbackService#degradedResult 注释）；降级仍属有效检索，照常记录历史
            searchMetrics.recordSearch(query.getType().getCode(), SearchMetrics.OUTCOME_DEGRADED);
            recordHistory(query);
            return fallbackService.degradedResult(query);
        }

        // 步骤4：无命中进入兜底链路
        if (result.getTotal() == null || result.getTotal() <= 0) {
            // 兜底结果也是有效检索：异步记录历史（未登录自动跳过）
            searchMetrics.recordSearch(query.getType().getCode(), SearchMetrics.OUTCOME_FALLBACK);
            recordHistory(query);
            return fallbackService.fallback(query);
        }

        // 步骤5：写热点缓存（write 内部再次校验 source=ES，兜底/降级结果不会写入）
        searchMetrics.recordSearch(query.getType().getCode(), SearchMetrics.OUTCOME_ES);
        hotQueryCacheService.write(cacheKey, result);
        // 步骤6：异步记录搜索历史（仅登录用户；历史记录为辅助能力，失败不影响检索）
        recordHistory(query);
        return result;
    }

    /**
     * 异步记录搜索历史（批次六 6.2）。
     *
     * <p>仅登录用户记录：未登录请求没有归属人，历史不落库；
     * 这里只取 userId 并委托 SearchHistoryService（方法内部 @Async + 吞异常）。</p>
     *
     * @param query 归一化后的检索参数（关键词已校验）
     */
    private void recordHistory(SearchQuery query) {
        if (!StpUtil.isLogin()) {
            return;
        }
        try {
            // getLoginIdAsLong 在登录态下返回当前用户 ID
            searchHistoryService.recordAsync(StpUtil.getLoginIdAsLong(), query.getKeyword());
        } catch (Exception e) {
            // 登录态解析失败等极端情况只告警，不影响检索结果返回
            log.warn("[搜索历史] 记录入口异常（不影响检索），keyword={}", query.getKeyword(), e);
        }
    }

    // ==================== ES 检索执行（查询/排序/高亮构建委托 queryBuilder） ====================

    /**
     * 按 type 路由索引并执行检索。
     *
     * @param query 归一化检索参数
     * @return 常规 ES 结果（source=ES）
     * @throws BizException ES 不可用（SEARCH_ES_UNAVAILABLE）
     */
    private SearchResultVO executeSearch(SearchQuery query) {
        try {
            return switch (query.getType()) {
                case ARTICLE -> searchArticles(query);
                case BOOK -> searchBooks(query);
                case USER -> searchUsers(query);
            };
        } catch (BizException e) {
            // 业务校验类异常原样抛出（不属于 ES 故障）
            throw e;
        } catch (Exception e) {
            log.error("[检索] ES 查询异常：type={}, q={}", query.getType().getCode(), query.getKeyword(), e);
            throw new BizException(ErrorCode.SEARCH_ES_UNAVAILABLE, "搜索服务暂不可用，请稍后重试", e);
        }
    }

    /**
     * 文章索引检索。
     *
     * @param query 检索参数
     * @return 文章结果
     * @throws Exception ES 访问异常
     */
    private SearchResultVO searchArticles(SearchQuery query) throws Exception {
        // DSL 构建（查询/排序/高亮/请求骨架）委托 queryBuilder，本方法只负责执行
        SearchRequest request = queryBuilder.buildRequest(properties.getIndex().getArticle(), query,
                queryBuilder.buildArticleQuery(query), queryBuilder.buildSorts(query, true),
                queryBuilder.buildArticleHighlight());
        SearchResponse<ArticleIndexDoc> response =
                elasticsearchClient.search(request, ArticleIndexDoc.class);
        SearchResultVO result = resultConverter.toArticleResult(response, query);
        result.setSource(SearchQueryConstant.SEARCH_SOURCE_ES);
        return result;
    }

    /**
     * 书籍索引检索。
     *
     * @param query 检索参数
     * @return 书籍结果
     * @throws Exception ES 访问异常
     */
    private SearchResultVO searchBooks(SearchQuery query) throws Exception {
        // DSL 构建（查询/排序/高亮/请求骨架）委托 queryBuilder，本方法只负责执行
        SearchRequest request = queryBuilder.buildRequest(properties.getIndex().getBook(), query,
                queryBuilder.buildBookQuery(query), queryBuilder.buildSorts(query, false),
                queryBuilder.buildBookHighlight());
        SearchResponse<BookIndexDoc> response =
                elasticsearchClient.search(request, BookIndexDoc.class);
        SearchResultVO result = resultConverter.toBookResult(response, query);
        result.setSource(SearchQueryConstant.SEARCH_SOURCE_ES);
        return result;
    }

    /**
     * 用户索引检索。
     *
     * @param query 检索参数
     * @return 用户结果
     * @throws Exception ES 访问异常
     */
    private SearchResultVO searchUsers(SearchQuery query) throws Exception {
        // DSL 构建（查询/排序/高亮/请求骨架）委托 queryBuilder，本方法只负责执行
        SearchRequest request = queryBuilder.buildRequest(properties.getIndex().getUser(), query,
                queryBuilder.buildUserQuery(query), queryBuilder.buildSorts(query, false),
                queryBuilder.buildUserHighlight());
        SearchResponse<UserIndexDoc> response =
                elasticsearchClient.search(request, UserIndexDoc.class);
        SearchResultVO result = resultConverter.toUserResult(response, query);
        result.setSource(SearchQueryConstant.SEARCH_SOURCE_ES);
        return result;
    }
}
