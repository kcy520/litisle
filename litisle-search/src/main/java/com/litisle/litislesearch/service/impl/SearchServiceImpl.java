package com.litisle.litislesearch.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScore;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MultiMatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.TermQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Highlight;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litisleapi.dto.search.SearchRequestDTO;
import com.litisle.litisleapi.vo.search.ArticleDocVO;
import com.litisle.litisleapi.vo.search.BookDocVO;
import com.litisle.litisleapi.vo.search.SearchResultVO;
import com.litisle.litisleapi.vo.search.UserDocVO;
import com.litisle.litislesearch.config.SearchProperties;
import com.litisle.litislesearch.constant.SearchBizConstant;
import com.litisle.litislesearch.domain.entity.ArticleIndexDoc;
import com.litisle.litislesearch.domain.entity.BookIndexDoc;
import com.litisle.litislesearch.domain.entity.UserIndexDoc;
import com.litisle.litislesearch.enums.SearchSortEnum;
import com.litisle.litislesearch.enums.SearchTypeEnum;
import com.litisle.litislesearch.service.HotQueryCacheService;
import com.litisle.litislesearch.service.SearchService;
import com.litisle.litislesearch.service.VectorService;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 站内检索服务实现（批次四 4.1 / 4.2）。
 *
 * 【主链路】参数校验 → 热点查询缓存 → 按 type 路由索引 → bool(must multi_match + filter) →
 * RELATED 排序附加 function_score(heat) → NEWEST/HEAT 字段排序 → 高亮 → 按 type 转换 VO。
 * 【兜底链路】主查询无命中 → 查询词实时 Embedding（受 fallback.embedding-timeout 超时约束）
 * → article_idx KNN（source=fallback）→ Embedding 超时/失败或 KNN 无命中 → article_idx HEAT 热门列表
 * （仍 source=fallback）→ 热门也失败 → 空页；兜底结果一律不写热点缓存。
 * 【安全】查询词仅走结构化 DSL（不使用 query_string），并按架构文档 §八 对保留字符做转义；
 * 排序字段只来自 SearchSortEnum 白名单，筛选字段只使用索引固定字段名，不透传用户输入构造 DSL。
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {

    /** 字段权重后缀分隔符（multi_match 的 title^4 语法，权重值取 weight.title 等配置） */
    private static final String WEIGHT_BOOST_SEPARATOR = "^";

    /** 查询词转义保留字符（架构文档 §八-1 清单；仅结构化 DSL 下的纵深防御） */
    private static final String ES_RESERVED_CHARS = "+-=&&||><!(){}[]^\"~*?:\\/";

    /** ES 日期字段查询值的格式（与 mapping date 默认 strict_date_optional_time 兼容） */
    private static final DateTimeFormatter ES_DATE_TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /** 日期筛选输入格式：纯日期（yyyy-MM-dd，长度 10，格式串由常量收口） */
    private static final DateTimeFormatter INPUT_DATE_FORMATTER =
            DateTimeFormatter.ofPattern(SearchBizConstant.PUBLISH_TIME_DATE_PATTERN);

    /** 日期筛选输入格式：空格分隔日期时间（yyyy-MM-dd HH:mm:ss，长度 19） */
    private static final DateTimeFormatter INPUT_DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern(SearchBizConstant.PUBLISH_TIME_DATETIME_PATTERN);

    /** ES 客户端（全文检索 / KNN / 热门列表查询） */
    private final ElasticsearchClient elasticsearchClient;

    /** search 配置（权重、分页上限、KNN 参数、兜底超时、索引名） */
    private final SearchProperties properties;

    /** 向量化服务（空结果兜底的查询词实时 Embedding；提供方未接入时返回 null） */
    private final VectorService vectorService;

    /** 热点查询缓存（命中直返 + 常规结果写缓存 + 索引变更失效） */
    private final HotQueryCacheService hotQueryCacheService;

    /**
     * 兜底 Embedding 线程池：VectorService 可能阻塞在远程模型调用，
     * 用独立线程池承载并配合 Future 超时，避免拖垮 Web 请求线程
     */
    private final ExecutorService embedExecutor = Executors.newFixedThreadPool(2, runnable -> {
        // 兜底线程设为守护线程，避免停机时因子任务阻塞退出
        Thread thread = new Thread(runnable, "search-embed-fallback");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 停机时释放兜底线程池。
     */
    @PreDestroy
    public void shutdown() {
        embedExecutor.shutdown();
    }

    /**
     * 执行检索（校验 → 缓存 → ES → 空结果兜底 → 写缓存）。
     *
     * @param request 检索请求
     * @return 检索结果
     */
    @Override
    public SearchResultVO search(SearchRequestDTO request) {
        // 步骤1：参数校验与归一化（q 必填/长度、type/sort 白名单、分页与深分页上限、时间范围）
        SearchQuery query = prepare(request);

        // 步骤2：热点查询缓存（仅常规 ES 命中结果会被写入；兜底结果不缓存）
        String cacheKey = hotQueryCacheService.buildKey(request, query.getType().getCode(),
                query.getSort().getCode(), query.getPageNum(), query.getPageSize(), query.getKeyword());
        SearchResultVO cached = hotQueryCacheService.read(cacheKey);
        if (cached != null) {
            return cached;
        }

        // 步骤3：执行 ES 检索（异常包装为 SEARCH_ES_UNAVAILABLE，降级链路批次八接入）
        SearchResultVO result = executeSearch(query);

        // 步骤4：无命中进入兜底链路
        if (result.getTotal() == null || result.getTotal() <= 0) {
            return fallback(query);
        }

        // 步骤5：写热点缓存（write 内部再次校验 source=ES，兜底/降级结果不会写入）
        hotQueryCacheService.write(cacheKey, result);
        return result;
    }

    // ==================== 参数校验与归一化 ====================

    /**
     * 参数校验与归一化。
     *
     * @param request 原始请求
     * @return 归一化后的检索参数
     */
    private SearchQuery prepare(SearchRequestDTO request) {
        // 请求体缺失：契约层未做校验，提供方兜底拒绝
        if (request == null) {
            throw new BizException(ErrorCode.SEARCH_PARAM_INVALID, "搜索请求不能为空");
        }
        // 查询词：trim 后非空、长度上限内、不含控制字符
        String keyword = normalizeKeyword(request.getQ());
        // 类型/排序：仅接受枚举 code/name（大小写不敏感），其余拒绝
        SearchTypeEnum type = resolveType(request.getType());
        SearchSortEnum sort = resolveSort(request.getSort());
        // 分页：缺省值兜底 + 单页上限 + 深分页窗口上限
        int pageNum = request.getPageNum() == null
                ? SearchBizConstant.SEARCH_DEFAULT_PAGE_NUM : request.getPageNum();
        int pageSize = request.getPageSize() == null
                ? SearchBizConstant.SEARCH_DEFAULT_PAGE_SIZE : request.getPageSize();
        validatePage(pageNum, pageSize);
        // 时间范围：支持 yyyy-MM-dd / yyyy-MM-dd HH:mm:ss / ISO-8601
        LocalDateTime publishBegin = parsePublishTime(request.getPublishBegin(), true);
        LocalDateTime publishEnd = parsePublishTime(request.getPublishEnd(), false);
        if (publishBegin != null && publishEnd != null && publishBegin.isAfter(publishEnd)) {
            throw new BizException(ErrorCode.SEARCH_PARAM_INVALID, "发布时间范围起止颠倒");
        }
        // from 在分页校验通过后计算（乘积已受 max-result-window 约束，不会溢出）
        int from = (pageNum - 1) * pageSize;
        return new SearchQuery(keyword, escapeKeyword(keyword), type, sort, pageNum, pageSize, from,
                publishBegin, publishEnd, request.getTag(), request.getCategory(), request.getZone());
    }

    /**
     * 校验并归一化查询词。
     *
     * @param raw 原始查询词
     * @return trim 后的查询词
     */
    private String normalizeKeyword(String raw) {
        // 必填校验：null/空白均拒绝
        if (!StringUtils.hasText(raw)) {
            throw new BizException(ErrorCode.SEARCH_PARAM_INVALID, "搜索关键词 q 不能为空");
        }
        String keyword = raw.trim();
        // 长度上限：与 search_history.keyword varchar(64) 同源
        if (keyword.length() > SearchBizConstant.SEARCH_KEYWORD_MAX_LENGTH) {
            throw new BizException(ErrorCode.SEARCH_PARAM_INVALID,
                    "搜索关键词长度不能超过 " + SearchBizConstant.SEARCH_KEYWORD_MAX_LENGTH + " 个字符");
        }
        // 控制字符拦截：不可见字符可能破坏日志与查询语义
        for (int i = 0; i < keyword.length(); i++) {
            if (Character.isISOControl(keyword.charAt(i))) {
                throw new BizException(ErrorCode.SEARCH_PARAM_INVALID, "搜索关键词包含非法控制字符");
            }
        }
        return keyword;
    }

    /**
     * 解析检索类型（缺省 article；非法值拒绝）。
     *
     * @param type 类型 code 或枚举名
     * @return 类型枚举
     */
    private SearchTypeEnum resolveType(String type) {
        if (!StringUtils.hasText(type)) {
            return SearchTypeEnum.ARTICLE;
        }
        String value = type.trim();
        for (SearchTypeEnum item : SearchTypeEnum.values()) {
            if (item.getCode().equalsIgnoreCase(value) || item.name().equalsIgnoreCase(value)) {
                return item;
            }
        }
        throw new BizException(ErrorCode.SEARCH_PARAM_INVALID, "type 仅支持 article/book/user");
    }

    /**
     * 解析排序方式（缺省 related；非法值拒绝）。
     *
     * @param sort 排序 code 或枚举名
     * @return 排序枚举
     */
    private SearchSortEnum resolveSort(String sort) {
        if (!StringUtils.hasText(sort)) {
            return SearchSortEnum.RELATED;
        }
        String value = sort.trim();
        for (SearchSortEnum item : SearchSortEnum.values()) {
            if (item.getCode().equalsIgnoreCase(value) || item.name().equalsIgnoreCase(value)) {
                return item;
            }
        }
        throw new BizException(ErrorCode.SEARCH_PARAM_INVALID, "sort 仅支持 related/newest/heat");
    }

    /**
     * 分页校验：单页上限 + 深分页窗口上限（pageNum × pageSize ≤ max-result-window）。
     *
     * @param pageNum  页码
     * @param pageSize 每页条数
     */
    private void validatePage(int pageNum, int pageSize) {
        if (pageNum < 1) {
            throw new BizException(ErrorCode.SEARCH_PAGE_EXCEEDED, "pageNum 必须从 1 开始");
        }
        if (pageSize < 1 || pageSize > properties.getPage().getMaxPageSize()) {
            throw new BizException(ErrorCode.SEARCH_PAGE_EXCEEDED,
                    "pageSize 必须在 1~" + properties.getPage().getMaxPageSize() + " 之间");
        }
        // long 乘法防溢出：深分页窗口上限与外置的 ES index.max_result_window 对齐
        long windowSize = (long) pageNum * pageSize;
        if (windowSize > properties.getPage().getMaxResultWindow()) {
            throw new BizException(ErrorCode.SEARCH_PAGE_EXCEEDED,
                    "pageNum×pageSize 不能超过 " + properties.getPage().getMaxResultWindow()
                            + "（ES 最大结果窗口）");
        }
    }

    /**
     * 解析发布时间筛选值。
     *
     * @param text  原始字符串（可为空）
     * @param begin true=范围起点（纯日期取当天 00:00:00）；false=范围终点（纯日期取当天 23:59:59.999999999）
     * @return 解析后的时间；未传返回 null
     */
    private LocalDateTime parsePublishTime(String text, boolean begin) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String value = text.trim();
        try {
            // 格式1：纯日期 yyyy-MM-dd（长度固定 10，由常量收口）
            if (value.length() == SearchBizConstant.PUBLISH_TIME_DATE_LENGTH) {
                LocalDate date = LocalDate.parse(value, INPUT_DATE_FORMATTER);
                return begin ? date.atStartOfDay() : date.atTime(LocalTime.MAX);
            }
            // 格式2：yyyy-MM-dd HH:mm:ss
            if (value.length() == SearchBizConstant.PUBLISH_TIME_DATETIME_PATTERN.length()) {
                return LocalDateTime.parse(value, INPUT_DATE_TIME_FORMATTER);
            }
            // 格式3：ISO-8601（如 2026-11-04T10:00:00）
            return LocalDateTime.parse(value, ES_DATE_TIME_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new BizException(ErrorCode.SEARCH_PARAM_INVALID,
                    "时间范围格式错误，支持 yyyy-MM-dd、yyyy-MM-dd HH:mm:ss 或 ISO-8601");
        }
    }

    /**
     * 查询词转义：对 ES 保留字符加反斜杠（架构文档 §八-1 纵深防御）。
     *
     * 【说明】本服务只使用结构化 DSL（multi_match，非 query_string），用户输入不会被解析为查询语法；
     * 转义是对未来演进（如引入 query_string）的防御，不影响现有 best_fields 匹配。
     *
     * @param keyword 归一化查询词
     * @return 转义后的查询词
     */
    private String escapeKeyword(String keyword) {
        StringBuilder escaped = new StringBuilder(keyword.length() * 2);
        for (int i = 0; i < keyword.length(); i++) {
            char ch = keyword.charAt(i);
            if (ES_RESERVED_CHARS.indexOf(ch) >= 0) {
                escaped.append('\\');
            }
            escaped.append(ch);
        }
        return escaped.toString();
    }

    // ==================== ES 检索 ====================

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
        SearchRequest request = buildRequest(properties.getIndex().getArticle(), query,
                buildArticleQuery(query), buildSorts(query, true), buildArticleHighlight());
        SearchResponse<ArticleIndexDoc> response =
                elasticsearchClient.search(request, ArticleIndexDoc.class);
        SearchResultVO result = toArticleResult(response, query);
        result.setSource(SearchBizConstant.SEARCH_SOURCE_ES);
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
        SearchRequest request = buildRequest(properties.getIndex().getBook(), query,
                buildBookQuery(query), buildSorts(query, false), buildBookHighlight());
        SearchResponse<BookIndexDoc> response =
                elasticsearchClient.search(request, BookIndexDoc.class);
        SearchResultVO result = toBookResult(response, query);
        result.setSource(SearchBizConstant.SEARCH_SOURCE_ES);
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
        SearchRequest request = buildRequest(properties.getIndex().getUser(), query,
                buildUserQuery(query), buildSorts(query, false), buildUserHighlight());
        SearchResponse<UserIndexDoc> response =
                elasticsearchClient.search(request, UserIndexDoc.class);
        SearchResultVO result = toUserResult(response, query);
        result.setSource(SearchBizConstant.SEARCH_SOURCE_ES);
        return result;
    }

    /**
     * 构建通用检索请求（分页、总数精确统计、查询、排序、高亮）。
     *
     * @param index     索引名
     * @param query     检索参数
     * @param esQuery   查询 DSL
     * @param sorts     排序（空列表表示按 _score 降序）
     * @param highlight 高亮配置
     * @return ES 检索请求
     */
    private SearchRequest buildRequest(String index, SearchQuery query, Query esQuery,
                                       List<SortOptions> sorts, Highlight highlight) {
        SearchRequest.Builder builder = new SearchRequest.Builder()
                .index(index)
                .from(query.getFrom())
                .size(query.getPageSize())
                // 精确总数：前端分页需要全量命中数（不允许 ES 默认的 10000 上限截断）
                .trackTotalHits(t -> t.enabled(true))
                .query(esQuery)
                .highlight(highlight);
        if (!sorts.isEmpty()) {
            builder.sort(sorts);
        }
        return builder.build();
    }

    /**
     * 文章查询：multi_match(权重 title^4/tags^3/summary^2/content) + visibility 双保险 + 筛选，
     * RELATED 排序附加 heat 的 function_score 加权。
     *
     * @param query 检索参数
     * @return 查询 DSL
     */
    private Query buildArticleQuery(SearchQuery query) {
        // 筛选：可见性双保险（写入侧已仅 PUBLIC，查询侧再兜底，防脏数据越权召回）
        List<Query> filters = new ArrayList<>();
        filters.add(termFilter(SearchBizConstant.FIELD_VISIBILITY, SearchBizConstant.VISIBILITY_PUBLIC));
        // 标签筛选（tags keyword 数组精确匹配）
        addTermFilter(filters, SearchBizConstant.FIELD_TAGS, query.getTag());
        // 分类/分区筛选（keyword）
        addTermFilter(filters, SearchBizConstant.FIELD_CATEGORY, query.getCategory());
        addTermFilter(filters, SearchBizConstant.FIELD_ZONE, query.getZone());
        // 时间范围筛选（updateTime 兼作发布时间语义，批次二口径）
        addTimeRangeFilter(filters, query);

        SearchProperties.Weight weight = properties.getWeight();
        Query multiMatch = MultiMatchQuery.of(mm -> mm
                .query(query.getEscapedKeyword())
                // 权重顺序：标题 ^4 → 标签 ^3 → 摘要 ^2 → 正文无权重
                .fields(weightedField(SearchBizConstant.FIELD_TITLE, weight.getTitle()),
                        weightedField(SearchBizConstant.FIELD_TAGS, weight.getTags()),
                        weightedField(SearchBizConstant.FIELD_SUMMARY, weight.getSummary()),
                        SearchBizConstant.FIELD_CONTENT)
                .type(TextQueryType.BestFields))._toQuery();

        Query bool = new BoolQuery.Builder()
                .must(List.of(multiMatch))
                .filter(filters)
                .build()._toQuery();
        // RELATED：相关度 + heat 加权；NEWEST/HEAT 走字段排序，无需 function_score
        return query.getSort() == SearchSortEnum.RELATED ? applyHeatBoost(bool) : bool;
    }

    /**
     * 书籍查询：multi_match(title/authorName/summary) + 分类/时间筛选。
     *
     * @param query 检索参数
     * @return 查询 DSL
     */
    private Query buildBookQuery(SearchQuery query) {
        List<Query> filters = new ArrayList<>();
        addTermFilter(filters, SearchBizConstant.FIELD_CATEGORY, query.getCategory());
        addTimeRangeFilter(filters, query);

        Query multiMatch = MultiMatchQuery.of(mm -> mm
                .query(query.getEscapedKeyword())
                .fields(SearchBizConstant.FIELD_TITLE, SearchBizConstant.FIELD_AUTHOR_NAME,
                        SearchBizConstant.FIELD_SUMMARY)
                .type(TextQueryType.BestFields))._toQuery();
        return new BoolQuery.Builder().must(List.of(multiMatch)).filter(filters).build()._toQuery();
    }

    /**
     * 用户查询：multi_match(penName/bio) + 时间筛选（用户索引无分类/分区/可见性字段）。
     *
     * @param query 检索参数
     * @return 查询 DSL
     */
    private Query buildUserQuery(SearchQuery query) {
        List<Query> filters = new ArrayList<>();
        addTimeRangeFilter(filters, query);

        Query multiMatch = MultiMatchQuery.of(mm -> mm
                .query(query.getEscapedKeyword())
                .fields(SearchBizConstant.FIELD_PEN_NAME, SearchBizConstant.FIELD_BIO)
                .type(TextQueryType.BestFields))._toQuery();
        return new BoolQuery.Builder().must(List.of(multiMatch)).filter(filters).build()._toQuery();
    }

    /**
     * 热度加权（function_score）：在相关度得分上叠加 log1p(heat)，避免超大 heat 直接压过文本相关性。
     *
     * @param base 基础查询
     * @return 加权后的查询
     */
    private Query applyHeatBoost(Query base) {
        FunctionScore function = FunctionScore.of(f -> f.fieldValueFactor(v -> v
                .field(SearchBizConstant.FIELD_HEAT)
                .factor(1.0)
                // log1p：热度越大增益越平缓，防旧爆款霸榜（衰减已在批次三刷写时计入 heat）
                .modifier(FieldValueFactorModifier.Log1p)
                // 文档缺 heat 时按 0 处理，避免加权把相关性清零
                .missing(0.0)));
        return FunctionScoreQuery.of(fs -> fs
                .query(base)
                .functions(List.of(function))
                .scoreMode(FunctionScoreMode.Sum)
                .boostMode(FunctionBoostMode.Sum))._toQuery();
    }

    /**
     * 构造排序（枚举白名单映射，不透传用户输入）。
     *
     * @param query       检索参数
     * @param articleType 是否文章索引（book/user 无 heat 字段）
     * @return 排序列表（空表示按 _score 降序）
     */
    private List<SortOptions> buildSorts(SearchQuery query, boolean articleType) {
        if (query.getSort() == SearchSortEnum.NEWEST) {
            return List.of(fieldSort(SearchBizConstant.FIELD_UPDATE_TIME));
        }
        if (query.getSort() == SearchSortEnum.HEAT) {
            // book/user 索引未映射 heat：回退更新时间降序，避免 ES 对未映射字段排序报错
            return List.of(fieldSort(articleType
                    ? SearchBizConstant.FIELD_HEAT : SearchBizConstant.FIELD_UPDATE_TIME));
        }
        return Collections.emptyList();
    }

    /**
     * 构造单字段降序排序。
     *
     * @param field 字段名（仅使用索引常量）
     * @return 排序项
     */
    private SortOptions fieldSort(String field) {
        return SortOptions.of(s -> s.field(f -> f.field(field).order(SortOrder.Desc)));
    }

    /**
     * 构造 term 精确过滤（值必须非空）。
     *
     * @param field 字段名（索引常量）
     * @param value 过滤值
     * @return 查询 DSL
     */
    private Query termFilter(String field, String value) {
        return TermQuery.of(t -> t.field(field).value(FieldValue.of(value)))._toQuery();
    }

    /**
     * 可选 term 过滤：值为空白时跳过（筛选条件全部可选）。
     *
     * @param filters 过滤器列表（就地追加）
     * @param field   字段名
     * @param value   过滤值
     */
    private void addTermFilter(List<Query> filters, String field, String value) {
        if (StringUtils.hasText(value)) {
            filters.add(termFilter(field, value.trim()));
        }
    }

    /**
     * 时间范围过滤（updateTime，收发边界包含）。
     *
     * @param filters 过滤器列表（就地追加）
     * @param query   检索参数
     */
    private void addTimeRangeFilter(List<Query> filters, SearchQuery query) {
        if (query.getPublishBegin() == null && query.getPublishEnd() == null) {
            return;
        }
        // 日期范围走 DateRangeQuery 联合类型（值用 ISO-8601 字符串，匹配 mapping date 默认格式）
        RangeQuery range = RangeQuery.of(r -> r.date(d -> {
            d.field(SearchBizConstant.FIELD_UPDATE_TIME);
            if (query.getPublishBegin() != null) {
                d.gte(query.getPublishBegin().format(ES_DATE_TIME_FORMATTER));
            }
            if (query.getPublishEnd() != null) {
                d.lte(query.getPublishEnd().format(ES_DATE_TIME_FORMATTER));
            }
            return d;
        }));
        filters.add(range._toQuery());
    }

    /**
     * 拼接 multi_match 字段权重（title^4 语法）。
     *
     * @param field  字段名
     * @param weight 权重（来自 litisle.search.weight.*）
     * @return 带权重的字段表达式
     */
    private String weightedField(String field, double weight) {
        return field + WEIGHT_BOOST_SEPARATOR + weight;
    }

    /**
     * 文章高亮配置（正文/摘要/标题，各取 1 段）。
     *
     * @return 高亮配置
     */
    private Highlight buildArticleHighlight() {
        Highlight.Builder builder = new Highlight.Builder();
        builder.fields(SearchBizConstant.FIELD_CONTENT, highlightField());
        builder.fields(SearchBizConstant.FIELD_SUMMARY, highlightField());
        builder.fields(SearchBizConstant.FIELD_TITLE, highlightField());
        return builder.build();
    }

    /**
     * 书籍高亮配置（简介/书名/作者名）。
     *
     * @return 高亮配置
     */
    private Highlight buildBookHighlight() {
        Highlight.Builder builder = new Highlight.Builder();
        builder.fields(SearchBizConstant.FIELD_SUMMARY, highlightField());
        builder.fields(SearchBizConstant.FIELD_TITLE, highlightField());
        builder.fields(SearchBizConstant.FIELD_AUTHOR_NAME, highlightField());
        return builder.build();
    }

    /**
     * 用户高亮配置（简介/笔名）。
     *
     * @return 高亮配置
     */
    private Highlight buildUserHighlight() {
        Highlight.Builder builder = new Highlight.Builder();
        builder.fields(SearchBizConstant.FIELD_BIO, highlightField());
        builder.fields(SearchBizConstant.FIELD_PEN_NAME, highlightField());
        return builder.build();
    }

    /**
     * 单字段高亮配置（片段长度与数量统一收口到常量）。
     *
     * @return 高亮字段配置
     */
    private HighlightField highlightField() {
        return HighlightField.of(f -> f
                .fragmentSize(SearchBizConstant.HIGHLIGHT_FRAGMENT_SIZE)
                .numberOfFragments(SearchBizConstant.HIGHLIGHT_FRAGMENT_COUNT));
    }

    // ==================== 空结果兜底（批次四 4.2） ====================

    /**
     * 空结果兜底：实时 Embedding → KNN；失败再退 HEAT 热门列表；仍失败返回空页。
     *
     * @param query 检索参数
     * @return 兜底结果（source=fallback，不写缓存）
     */
    private SearchResultVO fallback(SearchQuery query) {
        // 仅文章索引有 vec_dense / heat 字段；book/user 无可用兜底数据，直接返回空页
        if (query.getType() != SearchTypeEnum.ARTICLE) {
            log.info("[检索兜底] book/user 无兜底数据源，返回空页：type={}, q={}",
                    query.getType().getCode(), query.getKeyword());
            return emptyResult(query.getPageNum(), query.getPageSize());
        }
        // 一级兜底：查询词实时 Embedding（超时/失败返回 null）
        List<Float> vector = embedWithTimeout(query.getKeyword());
        if (vector != null && vector.size() == SearchBizConstant.EMBEDDING_DIMS) {
            try {
                SearchResultVO knnResult = knnFallback(query, vector);
                if (knnResult.getTotal() != null && knnResult.getTotal() > 0) {
                    return knnResult;
                }
            } catch (Exception e) {
                // KNN 失败（ES 抖动/查询异常）继续走二级兜底，不阻塞主链路
                log.warn("[检索兜底] KNN 检索失败，转 HEAT 热门列表：q={}", query.getKeyword(), e);
            }
        }
        // 二级兜底：article_idx 按 heat 降序（与批次八 A3 降级链路热门列表同口径）
        try {
            return heatFallback(query);
        } catch (Exception e) {
            // 热门列表也失败：返回空页（兜底失败不阻塞主链路）
            log.error("[检索兜底] HEAT 热门列表失败，返回空页：q={}", query.getKeyword(), e);
            return emptyResult(query.getPageNum(), query.getPageSize());
        }
    }

    /**
     * 一级兜底：查询词实时向量化（受 fallback.embedding-timeout 约束）。
     *
     * @param text 查询词（未转义原词）
     * @return 1024 维向量；超时/异常/提供方未接入时返回 null
     */
    private List<Float> embedWithTimeout(String text) {
        // 超时下限 1ms：配置异常（0/负数）时仍能快速失败而不是无限等待
        long timeoutMs = Math.max(1, properties.getFallback().getEmbeddingTimeout().toMillis());
        Future<List<Float>> future = embedExecutor.submit(() -> vectorService.embed(text));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 超时：取消任务并转入二级兜底
            future.cancel(true);
            log.warn("[检索兜底] 查询词向量化超时（{}ms），转 HEAT 热门列表：q={}", timeoutMs, text);
            return null;
        } catch (InterruptedException e) {
            // 线程中断：恢复中断标记后转入二级兜底
            Thread.currentThread().interrupt();
            log.warn("[检索兜底] 查询词向量化被中断，转 HEAT 热门列表：q={}", text);
            return null;
        } catch (Exception e) {
            // 模型调用失败/提供方未接入（返回 null 属正常降级路径）：转二级兜底
            log.warn("[检索兜底] 查询词向量化失败，转 HEAT 热门列表：q={}", text, e);
            return null;
        }
    }

    /**
     * KNN 兜底：以查询词向量在 article_idx 中做语义近邻检索。
     *
     * @param query  检索参数
     * @param vector 查询词向量
     * @return KNN 结果（source=fallback；仅返回第一页，KNN 不支持 from 深翻）
     * @throws Exception ES 访问异常
     */
    private SearchResultVO knnFallback(SearchQuery query, List<Float> vector) throws Exception {
        SearchProperties.Knn knnConfig = properties.getKnn();
        // k 必须 ≥ size，numCandidates 必须 ≥ k
        int k = query.getPageSize();
        int candidates = Math.max(knnConfig.getNumCandidates(), k);
        KnnSearch knn = KnnSearch.of(builder -> builder
                .field(SearchBizConstant.FIELD_VEC_DENSE)
                .queryVector(vector)
                .k(k)
                .numCandidates(candidates)
                // 可见性双保险：KNN 结果同样只允许 PUBLIC
                .filter(termFilter(SearchBizConstant.FIELD_VISIBILITY, SearchBizConstant.VISIBILITY_PUBLIC)));
        SearchRequest request = new SearchRequest.Builder()
                .index(properties.getIndex().getArticle())
                .size(k)
                .trackTotalHits(t -> t.enabled(true))
                .knn(knn)
                .highlight(buildArticleHighlight())
                .build();
        SearchResponse<ArticleIndexDoc> response =
                elasticsearchClient.search(request, ArticleIndexDoc.class);
        SearchResultVO result = toArticleResult(response, query);
        // 兜底结果固定第一页（KNN 无 from 翻页语义）
        result.setPageNum(1);
        result.setSource(SearchBizConstant.SEARCH_SOURCE_FALLBACK);
        return result;
    }

    /**
     * 二级兜底：article_idx 按 heat 降序取热门文章。
     *
     * @param query 检索参数
     * @return 热门列表（source=fallback）
     * @throws Exception ES 访问异常
     */
    private SearchResultVO heatFallback(SearchQuery query) throws Exception {
        SearchRequest request = new SearchRequest.Builder()
                .index(properties.getIndex().getArticle())
                .from(query.getFrom())
                .size(query.getPageSize())
                .trackTotalHits(t -> t.enabled(true))
                .query(termFilter(SearchBizConstant.FIELD_VISIBILITY, SearchBizConstant.VISIBILITY_PUBLIC))
                .sort(List.of(fieldSort(SearchBizConstant.FIELD_HEAT)))
                .highlight(buildArticleHighlight())
                .build();
        SearchResponse<ArticleIndexDoc> response =
                elasticsearchClient.search(request, ArticleIndexDoc.class);
        SearchResultVO result = toArticleResult(response, query);
        result.setSource(SearchBizConstant.SEARCH_SOURCE_FALLBACK);
        return result;
    }

    /**
     * 空结果（兜底失败或 book/user 无兜底数据）。
     *
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @return 空结果（source=fallback，三个命中列表均为空集合）
     */
    private SearchResultVO emptyResult(int pageNum, int pageSize) {
        return buildResult(pageNum, pageSize, SearchBizConstant.SEARCH_SOURCE_FALLBACK, 0L, null, null, null);
    }

    // ==================== 结果转换 ====================

    /**
     * 文章结果转换。
     *
     * @param response ES 响应
     * @param query    检索参数
     * @return 检索结果（source 由调用方设置）
     */
    private SearchResultVO toArticleResult(SearchResponse<ArticleIndexDoc> response, SearchQuery query) {
        List<ArticleDocVO> articles = new ArrayList<>();
        for (Hit<ArticleIndexDoc> hit : response.hits().hits()) {
            ArticleIndexDoc doc = hit.source();
            if (doc == null) {
                continue;
            }
            // 主键异常文档跳过（索引脏数据不应让整页失败）
            Long articleId = parseId(doc.getId(), SearchBizConstant.FIELD_ID);
            if (articleId == null) {
                continue;
            }
            ArticleDocVO vo = new ArticleDocVO();
            vo.setArticleId(articleId);
            vo.setTitle(doc.getTitle());
            vo.setSummary(doc.getSummary());
            vo.setTags(doc.getTags());
            vo.setAuthorId(parseId(doc.getAuthorId(), SearchBizConstant.FIELD_AUTHOR_ID));
            vo.setAuthorName(doc.getAuthorName());
            vo.setHeat(doc.getHeat() == null ? null : BigDecimal.valueOf(doc.getHeat()));
            vo.setUpdateTime(doc.getUpdateTime());
            // 高亮优先正文，其次摘要/标题
            vo.setHighlight(firstHighlight(hit.highlight(), List.of(
                    SearchBizConstant.FIELD_CONTENT, SearchBizConstant.FIELD_SUMMARY,
                    SearchBizConstant.FIELD_TITLE)));
            articles.add(vo);
        }
        return buildResult(query.getPageNum(), query.getPageSize(), SearchBizConstant.SEARCH_SOURCE_ES,
                totalOf(response, articles.size()), articles, null, null);
    }

    /**
     * 书籍结果转换。
     *
     * @param response ES 响应
     * @param query    检索参数
     * @return 检索结果（source 由调用方设置）
     */
    private SearchResultVO toBookResult(SearchResponse<BookIndexDoc> response, SearchQuery query) {
        List<BookDocVO> books = new ArrayList<>();
        for (Hit<BookIndexDoc> hit : response.hits().hits()) {
            BookIndexDoc doc = hit.source();
            if (doc == null) {
                continue;
            }
            Long bookId = parseId(doc.getId(), SearchBizConstant.FIELD_ID);
            if (bookId == null) {
                continue;
            }
            BookDocVO vo = new BookDocVO();
            vo.setBookId(bookId);
            vo.setTitle(doc.getTitle());
            vo.setSummary(doc.getSummary());
            vo.setCategory(doc.getCategory());
            vo.setAuthorName(doc.getAuthorName());
            // 批次五 B4 已补 book_idx.authorId 映射：解析回填作者 ID（存量未回填文档为 null）
            vo.setAuthorId(parseId(doc.getAuthorId(), SearchBizConstant.FIELD_AUTHOR_ID));
            vo.setUpdateTime(doc.getUpdateTime());
            vo.setHighlight(firstHighlight(hit.highlight(), List.of(
                    SearchBizConstant.FIELD_SUMMARY, SearchBizConstant.FIELD_TITLE,
                    SearchBizConstant.FIELD_AUTHOR_NAME)));
            books.add(vo);
        }
        return buildResult(query.getPageNum(), query.getPageSize(), SearchBizConstant.SEARCH_SOURCE_ES,
                totalOf(response, books.size()), null, books, null);
    }

    /**
     * 用户结果转换。
     *
     * @param response ES 响应
     * @param query    检索参数
     * @return 检索结果（source 由调用方设置）
     */
    private SearchResultVO toUserResult(SearchResponse<UserIndexDoc> response, SearchQuery query) {
        List<UserDocVO> users = new ArrayList<>();
        for (Hit<UserIndexDoc> hit : response.hits().hits()) {
            UserIndexDoc doc = hit.source();
            if (doc == null) {
                continue;
            }
            Long userId = parseId(doc.getId(), SearchBizConstant.FIELD_ID);
            if (userId == null) {
                continue;
            }
            UserDocVO vo = new UserDocVO();
            vo.setUserId(userId);
            vo.setPenName(doc.getPenName());
            vo.setBio(doc.getBio());
            vo.setHighlight(firstHighlight(hit.highlight(), List.of(
                    SearchBizConstant.FIELD_BIO, SearchBizConstant.FIELD_PEN_NAME)));
            users.add(vo);
        }
        return buildResult(query.getPageNum(), query.getPageSize(), SearchBizConstant.SEARCH_SOURCE_ES,
                totalOf(response, users.size()), null, null, users);
    }

    /**
     * 组装检索结果（三个命中列表只保留对应类型，其余为空集合）。
     *
     * @param pageNum     页码
     * @param pageSize    每页条数
     * @param source      结果来源
     * @param total       命中总数
     * @param articles    文章列表（非文章类型传 null）
     * @param books       书籍列表（非书籍类型传 null）
     * @param users       用户列表（非用户类型传 null）
     * @return 检索结果 VO
     */
    private SearchResultVO buildResult(int pageNum, int pageSize, String source, Long total,
                                       List<ArticleDocVO> articles, List<BookDocVO> books,
                                       List<UserDocVO> users) {
        SearchResultVO result = new SearchResultVO();
        result.setPageNum(pageNum);
        result.setPageSize(pageSize);
        result.setSource(source);
        result.setTotal(total);
        result.setArticleList(articles == null ? Collections.emptyList() : articles);
        result.setBookList(books == null ? Collections.emptyList() : books);
        result.setUserList(users == null ? Collections.emptyList() : users);
        return result;
    }

    /**
     * 取命中总数（ES total 缺失时退回当前页条数）。
     *
     * @param response ES 响应
     * @param fallback 当前页条数
     * @return 命中总数
     */
    private long totalOf(SearchResponse<?> response, int fallback) {
        return response.hits().total() == null ? fallback : response.hits().total().value();
    }

    /**
     * 提取首个高亮片段：按 preferred 字段顺序找，找不到再取任意字段。
     *
     * @param highlight 高亮结果（field → 片段列表）
     * @param preferred 字段优先级
     * @return 高亮片段；无高亮返回 null
     */
    private String firstHighlight(Map<String, List<String>> highlight, List<String> preferred) {
        if (highlight == null || highlight.isEmpty()) {
            return null;
        }
        for (String field : preferred) {
            List<String> fragments = highlight.get(field);
            if (fragments != null && !fragments.isEmpty()) {
                return fragments.get(0);
            }
        }
        for (List<String> fragments : highlight.values()) {
            if (fragments != null && !fragments.isEmpty()) {
                return fragments.get(0);
            }
        }
        return null;
    }

    /**
     * 文档主键字符串转 Long（非法值告警并跳过该条）。
     *
     * @param value  ES 文档主键
     * @param field  字段名（日志定位）
     * @return Long 主键；缺失或非法返回 null
     */
    private Long parseId(String value, String field) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            log.warn("[检索] 索引文档主键非法，跳过该条：field={}, value={}", field, value);
            return null;
        }
    }

    /**
     * 归一化后的检索参数（校验通过后固定下来，供查询构造与结果转换复用）。
     */
    @Getter
    private static class SearchQuery {
        /** 归一化查询词（未转义，参与缓存指纹与兜底向量化） */
        private final String keyword;
        /** 转义后的查询词（进 multi_match DSL） */
        private final String escapedKeyword;
        /** 检索类型 */
        private final SearchTypeEnum type;
        /** 排序方式 */
        private final SearchSortEnum sort;
        /** 页码（从 1 开始） */
        private final int pageNum;
        /** 每页条数 */
        private final int pageSize;
        /** from 偏移（(pageNum-1)×pageSize） */
        private final int from;
        /** 发布时间范围起点（可空） */
        private final LocalDateTime publishBegin;
        /** 发布时间范围终点（可空） */
        private final LocalDateTime publishEnd;
        /** 标签筛选（可空） */
        private final String tag;
        /** 分类筛选（可空） */
        private final String category;
        /** 分区筛选（可空） */
        private final String zone;

        SearchQuery(String keyword, String escapedKeyword, SearchTypeEnum type, SearchSortEnum sort,
                    int pageNum, int pageSize, int from, LocalDateTime publishBegin, LocalDateTime publishEnd,
                    String tag, String category, String zone) {
            this.keyword = keyword;
            this.escapedKeyword = escapedKeyword;
            this.type = type;
            this.sort = sort;
            this.pageNum = pageNum;
            this.pageSize = pageSize;
            this.from = from;
            this.publishBegin = publishBegin;
            this.publishEnd = publishEnd;
            this.tag = tag;
            this.category = category;
            this.zone = zone;
        }
    }
}
