package com.litisle.litislesearch.service.support;

import co.elastic.clients.elasticsearch._types.FieldValue;
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
import co.elastic.clients.elasticsearch.core.search.Highlight;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import com.litisle.litislesearch.config.SearchProperties;
import com.litisle.litislesearch.constant.SearchFieldConstant;
import com.litisle.litislesearch.constant.SearchGateConstant;
import com.litisle.litislesearch.constant.SearchQueryConstant;
import com.litisle.litislesearch.enums.SearchSortEnum;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ES 查询 DSL 构建组件（批次四 4.1，自 SearchServiceImpl 拆出）。
 *
 * 【职责边界】本类只负责构建不触网的 ES 请求对象：按 type 组装 multi_match + filter 的 bool 查询、
 * RELATED 排序的 heat 加权（function_score）、字段排序、高亮配置与通用 SearchRequest 骨架；
 * 不执行检索、不做结果转换、不做参数校验（归一化参数由 {@link SearchQuery} 传入）。
 * 执行检索由 SearchServiceImpl / SearchFallbackService 持 ElasticsearchClient 完成。
 *
 * 【安全】查询词仅进入结构化 DSL（multi_match，非 query_string）；
 * 排序字段只来自 SearchSortEnum 白名单映射，筛选字段只使用索引固定字段名，不透传用户输入构造 DSL。
 *
 * 【配置】字段权重取 litisle.search.weight.*（title/tags/summary），外置可调。
 *
 * @author litisle
 */
@Component
public class EsSearchQueryBuilder {

    /** 字段权重后缀分隔符（multi_match 的 title^4 语法，权重值取 weight.title 等配置） */
    private static final String WEIGHT_BOOST_SEPARATOR = "^";

    /** ES 日期字段查询值的格式（与 mapping date 默认 strict_date_optional_time 兼容） */
    private static final DateTimeFormatter ES_DATE_TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /** search 配置（multi_match 字段权重取 weight.*） */
    private final SearchProperties properties;

    /**
     * 构造组件并注入配置。
     *
     * @param properties search 配置
     */
    public EsSearchQueryBuilder(SearchProperties properties) {
        this.properties = properties;
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
    public SearchRequest buildRequest(String index, SearchQuery query, Query esQuery,
                                      List<SortOptions> sorts, Highlight highlight) {
        SearchRequest.Builder builder = new SearchRequest.Builder()
                .index(index)
                .from(query.getFrom())
                .size(query.getPageSize())
                // 精确总数：前端分页需要全量命中数（不允许 ES 默认的 10000 上限截断）
                .trackTotalHits(t -> t.enabled(true))
                .query(esQuery)
                .highlight(highlight);
        // 排序为空表示按相关度 _score 降序（RELATED 默认路径），不传 sort 让 ES 取默认
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
    public Query buildArticleQuery(SearchQuery query) {
        // 筛选：可见性双保险（写入侧已仅 PUBLIC，查询侧再兜底，防脏数据越权召回）
        List<Query> filters = new ArrayList<>();
        filters.add(termFilter(SearchFieldConstant.FIELD_VISIBILITY, SearchGateConstant.VISIBILITY_PUBLIC));
        // 标签筛选（tags keyword 数组精确匹配）
        addTermFilter(filters, SearchFieldConstant.FIELD_TAGS, query.getTag());
        // 分类/分区筛选（keyword）
        addTermFilter(filters, SearchFieldConstant.FIELD_CATEGORY, query.getCategory());
        addTermFilter(filters, SearchFieldConstant.FIELD_ZONE, query.getZone());
        // 时间范围筛选（updateTime 兼作发布时间语义，批次二口径）
        addTimeRangeFilter(filters, query);

        // 字段权重取配置（title^4/tags^3/summary^2/正文无权重），Nacos 可调
        SearchProperties.Weight weight = properties.getWeight();
        Query multiMatch = MultiMatchQuery.of(mm -> mm
                .query(query.getEscapedKeyword())
                // 权重顺序：标题 ^4 → 标签 ^3 → 摘要 ^2 → 正文无权重
                .fields(weightedField(SearchFieldConstant.FIELD_TITLE, weight.getTitle()),
                        weightedField(SearchFieldConstant.FIELD_TAGS, weight.getTags()),
                        weightedField(SearchFieldConstant.FIELD_SUMMARY, weight.getSummary()),
                        SearchFieldConstant.FIELD_CONTENT)
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
    public Query buildBookQuery(SearchQuery query) {
        List<Query> filters = new ArrayList<>();
        // 书籍无可见性/分区字段：只保留分类与时间范围筛选
        addTermFilter(filters, SearchFieldConstant.FIELD_CATEGORY, query.getCategory());
        addTimeRangeFilter(filters, query);

        Query multiMatch = MultiMatchQuery.of(mm -> mm
                .query(query.getEscapedKeyword())
                .fields(SearchFieldConstant.FIELD_TITLE, SearchFieldConstant.FIELD_AUTHOR_NAME,
                        SearchFieldConstant.FIELD_SUMMARY)
                .type(TextQueryType.BestFields))._toQuery();
        return new BoolQuery.Builder().must(List.of(multiMatch)).filter(filters).build()._toQuery();
    }

    /**
     * 用户查询：multi_match(penName/bio) + 时间筛选（用户索引无分类/分区/可见性字段）。
     *
     * @param query 检索参数
     * @return 查询 DSL
     */
    public Query buildUserQuery(SearchQuery query) {
        List<Query> filters = new ArrayList<>();
        // 用户索引只有时间范围可用筛选
        addTimeRangeFilter(filters, query);

        Query multiMatch = MultiMatchQuery.of(mm -> mm
                .query(query.getEscapedKeyword())
                .fields(SearchFieldConstant.FIELD_PEN_NAME, SearchFieldConstant.FIELD_BIO)
                .type(TextQueryType.BestFields))._toQuery();
        return new BoolQuery.Builder().must(List.of(multiMatch)).filter(filters).build()._toQuery();
    }

    /**
     * 构造排序（枚举白名单映射，不透传用户输入）。
     *
     * @param query       检索参数
     * @param articleType 是否文章索引（book/user 无 heat 字段）
     * @return 排序列表（空表示按 _score 降序）
     */
    public List<SortOptions> buildSorts(SearchQuery query, boolean articleType) {
        if (query.getSort() == SearchSortEnum.NEWEST) {
            // 最新排序：按更新时间降序
            return List.of(fieldSort(SearchFieldConstant.FIELD_UPDATE_TIME));
        }
        if (query.getSort() == SearchSortEnum.HEAT) {
            // book/user 索引未映射 heat：回退更新时间降序，避免 ES 对未映射字段排序报错
            return List.of(fieldSort(articleType
                    ? SearchFieldConstant.FIELD_HEAT : SearchFieldConstant.FIELD_UPDATE_TIME));
        }
        // RELATED：不加 sort，按 _score 降序（heat 加权已并入 function_score）
        return Collections.emptyList();
    }

    /**
     * 文章高亮配置（正文/摘要/标题，各取 1 段）。
     *
     * @return 高亮配置
     */
    public Highlight buildArticleHighlight() {
        Highlight.Builder builder = new Highlight.Builder();
        builder.fields(SearchFieldConstant.FIELD_CONTENT, highlightField());
        builder.fields(SearchFieldConstant.FIELD_SUMMARY, highlightField());
        builder.fields(SearchFieldConstant.FIELD_TITLE, highlightField());
        return builder.build();
    }

    /**
     * 书籍高亮配置（简介/书名/作者名）。
     *
     * @return 高亮配置
     */
    public Highlight buildBookHighlight() {
        Highlight.Builder builder = new Highlight.Builder();
        builder.fields(SearchFieldConstant.FIELD_SUMMARY, highlightField());
        builder.fields(SearchFieldConstant.FIELD_TITLE, highlightField());
        builder.fields(SearchFieldConstant.FIELD_AUTHOR_NAME, highlightField());
        return builder.build();
    }

    /**
     * 用户高亮配置（简介/笔名）。
     *
     * @return 高亮配置
     */
    public Highlight buildUserHighlight() {
        Highlight.Builder builder = new Highlight.Builder();
        builder.fields(SearchFieldConstant.FIELD_BIO, highlightField());
        builder.fields(SearchFieldConstant.FIELD_PEN_NAME, highlightField());
        return builder.build();
    }

    /**
     * 构造 term 精确过滤（值必须非空）。
     *
     * <p>公开给 KNN 兜底复用（可见性双保险过滤），避免同类过滤逻辑重复实现。</p>
     *
     * @param field 字段名（索引常量）
     * @param value 过滤值
     * @return 查询 DSL
     */
    public Query termFilter(String field, String value) {
        return TermQuery.of(t -> t.field(field).value(FieldValue.of(value)))._toQuery();
    }

    /**
     * 热度加权（function_score）：在相关度得分上叠加 log1p(heat)，避免超大 heat 直接压过文本相关性。
     *
     * @param base 基础查询
     * @return 加权后的查询
     */
    private Query applyHeatBoost(Query base) {
        FunctionScore function = FunctionScore.of(f -> f.fieldValueFactor(v -> v
                .field(SearchFieldConstant.FIELD_HEAT)
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
     * 构造单字段降序排序。
     *
     * @param field 字段名（仅使用索引常量）
     * @return 排序项
     */
    private SortOptions fieldSort(String field) {
        return SortOptions.of(s -> s.field(f -> f.field(field).order(SortOrder.Desc)));
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
        // 起止均未传：不加时间筛选
        if (query.getPublishBegin() == null && query.getPublishEnd() == null) {
            return;
        }
        // 日期范围走 DateRangeQuery 联合类型（值用 ISO-8601 字符串，匹配 mapping date 默认格式）
        RangeQuery range = RangeQuery.of(r -> r.date(d -> {
            d.field(SearchFieldConstant.FIELD_UPDATE_TIME);
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
     * 单字段高亮配置（片段长度与数量统一收口到常量）。
     *
     * @return 高亮字段配置
     */
    private HighlightField highlightField() {
        return HighlightField.of(f -> f
                .fragmentSize(SearchQueryConstant.HIGHLIGHT_FRAGMENT_SIZE)
                .numberOfFragments(SearchQueryConstant.HIGHLIGHT_FRAGMENT_COUNT));
    }
}
