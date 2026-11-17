package com.litisle.litislesearch.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.TermQuery;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litisleapi.config.AiLabelDimensionsProperties;
import com.litisle.litisleapi.dto.search.SimilarConditionRequestDTO;
import com.litisle.litisleapi.vo.search.SimilarArticleVO;
import com.litisle.litislesearch.config.SearchProperties;
import com.litisle.litislesearch.constant.SearchFieldConstant;
import com.litisle.litislesearch.constant.SearchGateConstant;
import com.litisle.litislesearch.domain.entity.ArticleIndexDoc;
import com.litisle.litislesearch.service.RerankService;
import com.litisle.litislesearch.service.SimilarService;
import com.litisle.litislesearch.service.VectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 相似文章推荐实现（批次七 7.1 + 板块三 3.1 条件版）。
 *
 * 【流程】源文章回查（vec_dense + tags）→ KNN（num_candidates、cosine）
 * → 融合评分（向量相似 + 标签重合度 + 行为热度，权重外置）
 * → 相似度阈值过滤 → 按融合分降序截断 topK。
 *
 * 【条件版（板块三 3.1）】按维度注册表 match 类型路由四类查询构造：filter 词表型 term、
 * range 数值型 range、param 型直接映射（topK/sortHint）、semantic 型 queryText embed 后
 * knn 作参照向量；queryText 为空退化纯条件过滤检索；similarity-first 融合权重置纯向量。
 *
 * 【rerank 精排（板块三 3.2 升级②）】queryText 非空且排序意图为相关性类（related/similarity-first）时，
 * 多取 rerank.top-n 候选送本地 reranker 重排（0 次 LLM）后截回 topK；
 * newest/heat 是用户显式排序意图不重排；未启用/失败 fail-open 退回融合基线排序，结果不劣化。
 *
 * 【安全】KNN filter 强制 visibility=PUBLIC 且 must_not 排除自身（批次C C3）；
 * 下架文章在批次二已被删除索引文档，因此 PUBLIC 过滤即可覆盖「排除已下架」。
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimilarServiceImpl implements SimilarService {

    /** 排序意图：按相关性融合分（默认） */
    private static final String SORT_RELATED = "related";

    /** 排序意图：最新发布优先 */
    private static final String SORT_NEWEST = "newest";

    /** 排序意图：热度优先 */
    private static final String SORT_HEAT = "heat";

    /** 排序意图：纯向量相似优先（融合权重置向量 1 其余归零） */
    private static final String SORT_SIMILARITY_FIRST = "similarity-first";

    /** ES 客户端（源文章回查 + KNN 检索） */
    private final ElasticsearchClient elasticsearchClient;

    /** search 配置（索引名 + KNN/融合权重参数） */
    private final SearchProperties properties;

    /** 向量化服务（条件版 semantic 维度：queryText 实时向量化；未就绪返回 null 退化纯条件过滤） */
    private final VectorService vectorService;

    /** 维度注册表（条件版四类 match 路由唯一数据源，板块三 3.1） */
    private final AiLabelDimensionsProperties dimensionsProperties;

    /** rerank 精排服务（板块三 3.2 升级②：条件版查询本地重排，未启用/失败退回基线排序） */
    private final RerankService rerankService;

    /**
     * 查询相似文章。
     *
     * <p>通俗地说：拿这篇文章去 ES 索引里找「内容最像」的其他文章。做法：先取这篇文章自己的向量
     * （还没生成向量就返回空列表），再用它做 KNN 近邻搜索（只搜公开文章、排除自己），
     * 最后按「向量相似度 + 标签重合度 + 热度」三路加权算综合分，分数最高的 topK 篇返回。</p>
     *
     * @param articleId 源文章 ID
     * @param topK      返回条数（可空）
     * @return 相似文章列表（按融合分降序）
     */
    @Override
    public List<SimilarArticleVO> similar(Long articleId, Integer topK) {

        //        入参校验（articleId 必须为正 + topK 截到配置上限内）
        //   ↓
        //        回查源文章（不存在/不公开报错；还没生成向量直接返回空列表）
        //   ↓
        //        KNN 检索（只搜公开文章 + 排除自身 + 多取 1 条防名额被占）
        //   ↓
        //        三路融合打分 → 相似度阈值过滤 → 按融合分降序截断 topK

        // 步骤1：参数校验（文章 ID 必须为正；topK 越界由配置上限截断）
        if (articleId == null || articleId <= 0) {
            throw new BizException(ErrorCode.SEARCH_PARAM_INVALID, "articleId 必须为正整数");
        }
        int limit = resolveTopK(topK);

        // 步骤2：回查源文章（不存在/已下架返回 SEARCH_SOURCE_NOT_FOUND，由调用方决策）
        ArticleIndexDoc source = fetchSource(articleId);
        // 防御：写入侧只入 PUBLIC，查询侧再兜底，防脏数据把私密文章当推荐源
        if (!SearchGateConstant.VISIBILITY_PUBLIC.equals(source.getVisibility())) {
            throw new BizException(ErrorCode.SEARCH_SOURCE_NOT_FOUND, "源文章不可公开推荐：" + articleId);
        }
        // 源文章暂无向量（Embedding 未接入或尚未生成）：返回空列表，让调用方按「无相似内容」展示
        if (source.getVecDense() == null || source.getVecDense().isEmpty()) {
            log.info("[相似推荐] 源文章无向量，返回空列表：articleId={}", articleId);
            return Collections.emptyList();
        }

        // 步骤3：KNN 检索（强制 PUBLIC + 排除自身）
        List<Hit<ArticleIndexDoc>> hits = knnSearch(source, limit);
        // 步骤4：融合评分、阈值过滤、降序截断
        return fuseAndLimit(hits, source, limit);
    }

    /**
     * 条件版相似文章查询（板块三 3.1：四类维度 match 组合查询）。
     *
     * <p>通俗地说：调用方给一段查询文字和一组筛选条件（作者/分类/标签/字数区间/发布时间区间等），
     * 本方法把查询文字变成向量、把每个条件翻译成 ES 过滤条件，然后带条件检索——有查询文字走
     * 「向量相似 + 过滤」，没有就退化为「纯过滤 + 按指定方式排序」。开了 rerank 精排且排序意图是
     * 相关性类时，先多取一批候选，交给重排模型按查询文字重新打分排序再截回 topK；
     * 重排失败退回原顺序，只是排序效果略降，不会报错。</p>
     *
     * <p>【业务逻辑步骤】
     * 1. 参数校验（请求体非空、topK 截断、sortHint 归一化）；
     * 2. 语义向量化（queryText 非空时 embed 作 knn 参照向量；embedding 未就绪退化纯条件过滤）；
     * 3. 按维度注册表路由构造条件 filter（filter 词表型 term / range 数值型 range）；
     * 4. 检索（有向量走 knn + filter；无向量退化纯条件过滤检索按 sortHint 排序）；
     * 5. 阈值过滤（仅向量路径）+ 融合/排序 + 截断，无命中返回空列表不报错；
     * 6. rerank 精排（3.2 升级②：queryText 非空且相关性类排序意图时本地重排，失败退回基线）。</p>
     *
     * @param dto 条件检索请求（各条件均可空）
     * @return 相似文章列表（按 sortHint 对应排序；无命中为空列表不报错）
     */
    @Override
    public List<SimilarArticleVO> similarByCondition(SimilarConditionRequestDTO dto) {

        //        参数校验（请求体必传 + topK 截断 + sortHint 归一化）
        //   ↓
        //        queryText 向量化（为空或向量服务未就绪 → 走纯条件过滤路径）
        //   ↓
        //        各条件翻译成 ES filter（注册表路由 + visibility=PUBLIC 铁律 + 白黑名单）
        //   ↓
        //        检索（有参照向量：knn + filter；无向量：纯 filter + 按排序意图排序）
        //   ↓
        //        融合基线排序（sortHint 决定打分权重与排序字段）
        //   ↓
        //        rerank 精排（仅相关性类意图且开关打开；失败退回基线顺序）→ 截回 topK

        // 步骤1：参数校验（请求体必传；topK 越界由配置上限截断，与 similar 同口径）
        if (dto == null) {
            throw new BizException(ErrorCode.SEARCH_PARAM_INVALID, "条件检索请求不能为空");
        }
        int limit = resolveTopK(dto.getTopK());
        // param 型 sortHint 归一化（空/非法值归一为 related，由 ai 提取侧词表校验兜底，此处双保险）
        String sortHint = normalizeSortHint(dto.getSortHint());
        // 步骤2：语义向量化（semantic 维度：queryText 作 knn 参照向量；embed 未就绪返回 null 退化纯条件过滤）
        List<Float> queryVector = StringUtils.hasText(dto.getQueryText())
                ? vectorService.embed(dto.getQueryText()) : null;
        // 步骤3：按维度注册表路由构造条件 filter（visibility=PUBLIC 铁律 + 各维度过滤）
        List<Query> filters = buildConditionFilters(dto);
        // rerank 触发条件（3.2 升级②）：queryText 非空 + 开关/提供方就绪 + 排序意图为相关性类
        // （newest/heat 是用户显式排序意图，重排会违背意图，不做）
        boolean rerankApplicable = StringUtils.hasText(dto.getQueryText())
                && rerankService.enabled()
                && !SORT_NEWEST.equals(sortHint) && !SORT_HEAT.equals(sortHint);
        // 检索条数：送重排时按 rerank.top-n 多取候选（重排后截回 topK），上限 maxTopK 防越权拉取
        int fetchSize = rerankApplicable
                ? Math.min(Math.max(limit, properties.getRerank().getTopN()), Math.max(1, properties.getKnn().getMaxTopK()))
                : limit;
        // 步骤4：检索——有参照向量走 knn + filter；无向量退化纯条件过滤检索（ES 侧按 sortHint 排序）
        List<Hit<ArticleIndexDoc>> hits = queryVector != null
                ? knnSearchByVector(queryVector, dto.getExcludeArticleId(), dto.getExcludeArticleIds(), filters, fetchSize)
                : filterSearch(dto.getExcludeArticleId(), dto.getExcludeArticleIds(), filters, fetchSize, sortHint);
        // 步骤5：阈值过滤 + 融合/排序 + 截断（无命中返回空列表不报错）——先产出融合基线排序
        List<SimilarArticleVO> baseline = fuseBySortHint(hits, dto.getExcludeArticleId(),
                dto.getExcludeArticleIds(), fetchSize, sortHint, queryVector != null);
        // 步骤6：rerank 精排（0 次 LLM；未启用/失败 fail-open 退回基线截断 topK，结果不劣化）
        if (rerankApplicable) {
            return rerankService.rerank(dto.getQueryText(), baseline, limit);
        }
        return baseline;
    }

    /**
     * 回查文章 AI 标签（板块十二 4.6：读 article_idx 单篇 ai_labels 映射，未入索引/未打标返回空 map）。
     *
     * <p>通俗地说：按文章 ID 去 ES 索引里取出这篇文章的 AI 标签映射（维度名 → 标签值）交给调用方。
     * 文章没入索引、已下架或没打过标签，都按「没有标签」处理返回空 map，不算错误；
     * ES 本身故障则按服务不可用上抛。</p>
     */
    @Override
    public java.util.Map<String, String> getArticleAiLabels(Long articleId) {
        // 参数校验：非法 ID 直接空 map（无标签语义，不打 ES）
        if (articleId == null || articleId <= 0) {
            return java.util.Map.of();
        }
        try {
            GetResponse<ArticleIndexDoc> response = elasticsearchClient.get(
                    g -> g.index(properties.getIndex().getArticle()).id(String.valueOf(articleId)),
                    ArticleIndexDoc.class);
            // 文档不存在（未入索引/已下架）：无标签语义返回空 map，不按错误处理
            if (!response.found() || response.source() == null) {
                return java.util.Map.of();
            }
            Map<String, String> labels = response.source().getAiLabels();
            return labels == null ? java.util.Map.of() : labels;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // ES/网络异常：标签回查属核心读依赖，fail-fast 上抛由调用方决策
            log.error("[AI 标签回查] ES 查询失败：articleId={}", articleId, e);
            throw new BizException(ErrorCode.SEARCH_ES_UNAVAILABLE, "搜索服务暂不可用，请稍后重试", e);
        }
    }

    /**
     * 归一化排序意图（param 型维度路由：空/非法值归一为 related 默认融合排序）
     *
     * <p>通俗地说：把请求里的排序说法统一成系统认识的四个取值之一；没填或填了不认识的值，
     * 就默认按综合相关性（related）排序，保证后续代码拿到的值一定合法。</p>
     *
     * @param sortHint 请求排序意图（可空）
     * @return 合法排序意图常量
     */
    private String normalizeSortHint(String sortHint) {
        if (!StringUtils.hasText(sortHint)) {
            return SORT_RELATED;
        }
        return switch (sortHint) {
            case SORT_NEWEST -> SORT_NEWEST;
            case SORT_HEAT -> SORT_HEAT;
            case SORT_SIMILARITY_FIRST -> SORT_SIMILARITY_FIRST;
            default -> SORT_RELATED;
        };
    }

    /**
     * 按维度注册表路由构造条件 filter（板块三 3.1 四类 match 中的 filter/range 两类落 ES 查询）
     *
     * <p>通俗地说：把请求里的每个条件逐个翻译成 ES 过滤条件，文章必须同时满足全部条件才可能被
     * 检索到。可见性必须公开是硬性规则；注册表里没登记的维度直接忽略，防止请求拿任意字段
     * 当过滤条件污染查询。</p>
     *
     * <p>【路由口径】注册表外 key 忽略（防未注册维度污染查询）；filter 维度缺省写
     * ai_labels.{维度名}，声明 esField 的写既有字段（category/tags）；range 维度绑定注册表
     * esField（word_count→word_count、publish_time→updateTime）；visibility=PUBLIC 铁律强制。</p>
     *
     * @param dto 条件检索请求
     * @return ES filter 查询列表
     */
    private List<Query> buildConditionFilters(SimilarConditionRequestDTO dto) {
        List<Query> filters = new ArrayList<>();
        // visibility=PUBLIC 铁律（与 similar 同口径，写入侧闸门 + 查询侧双保险）
        filters.add(termFilter(SearchFieldConstant.FIELD_VISIBILITY, SearchGateConstant.VISIBILITY_PUBLIC));
        // filter 维度：指定作者（authorId keyword 精确过滤）
        if (dto.getAuthorId() != null) {
            filters.add(termFilter(SearchFieldConstant.FIELD_AUTHOR_ID, String.valueOf(dto.getAuthorId())));
        }
        // filter 维度：分类（既有 keyword 字段）
        if (StringUtils.hasText(dto.getCategory())) {
            filters.add(termFilter(SearchFieldConstant.FIELD_CATEGORY, dto.getCategory().trim()));
        }
        // filter 维度：标签（keyword 数组 terms 交集过滤）
        if (dto.getTags() != null && !dto.getTags().isEmpty()) {
            filters.add(termsFilter(SearchFieldConstant.FIELD_TAGS, dto.getTags()));
        }
        // filter 词表型 ai_labels 维度路由（注册表驱动：新维度零代码）
        if (dimensionsProperties.isEnabled() && dto.getAiLabels() != null) {
            for (Map.Entry<String, String> entry : dto.getAiLabels().entrySet()) {
                AiLabelDimensionsProperties.Dimension dim =
                        dimensionsProperties.getDimensions().get(entry.getKey());
                // 注册表外 key / 非 filter 型维度：忽略（防未注册维度污染查询）
                if (dim == null || !"filter".equals(dim.getMatch())
                        || !StringUtils.hasText(entry.getValue())) {
                    continue;
                }
                // 缺省写 ai_labels.{维度名}；声明 esField 的写既有字段
                String field = StringUtils.hasText(dim.getEsField())
                        ? dim.getEsField() : SearchFieldConstant.FIELD_AI_LABELS + "." + entry.getKey();
                filters.add(termFilter(field, entry.getValue().trim()));
            }
        }
        // range 数值型维度：wordCount 区间（注册表 word_count 维度绑定字段路由）
        AiLabelDimensionsProperties.Dimension wordCountDim = dimensionsProperties.getDimensions().get("word_count");
        String wordCountField = wordCountDim != null && StringUtils.hasText(wordCountDim.getEsField())
                ? wordCountDim.getEsField() : SearchFieldConstant.FIELD_WORD_COUNT;
        addNumberRangeFilter(filters, wordCountField, dto.getWordCountMin(), dto.getWordCountMax());
        // range 数值型维度：发布时间区间（注册表 publish_time 维度绑定字段路由；解析失败该条件忽略不报错）
        AiLabelDimensionsProperties.Dimension publishTimeDim = dimensionsProperties.getDimensions().get("publish_time");
        String publishTimeField = publishTimeDim != null && StringUtils.hasText(publishTimeDim.getEsField())
                ? publishTimeDim.getEsField() : SearchFieldConstant.FIELD_UPDATE_TIME;
        addDateRangeFilter(filters, publishTimeField, dto.getPublishTimeStart(), dto.getPublishTimeEnd());
        // 白名单 filter（板块四 4.1：must terms 只在指定集合内检索——收藏夹语义检索/组合检索场景）
        if (dto.getIncludeArticleIds() != null && !dto.getIncludeArticleIds().isEmpty()) {
            filters.add(idTermsFilter(SearchFieldConstant.FIELD_ID, dto.getIncludeArticleIds()));
        }
        return filters;
    }

    /**
     * 构造文章 ID terms 过滤（板块四 4.1：白名单 must / 黑名单 must_not 共用构造器，Long 转字符串 FieldValue）
     *
     * <p>通俗地说：把一串文章 ID 转成 ES 的 terms 查询——放进 must 是「只准是这几个 ID 之一」
     * （白名单），放进 must_not 是「不准是这几个 ID 中任何一个」（黑名单）。数字转字符串
     * 是为了与 ES 中 keyword 类型主键的存储格式对齐。</p>
     *
     * @param field 字段名（索引常量，当前为主键 id）
     * @param ids   文章 ID 列表
     * @return 查询 DSL
     */
    private Query idTermsFilter(String field, List<Long> ids) {
        List<FieldValue> fieldValues = ids.stream()
                .filter(java.util.Objects::nonNull)
                .map(id -> FieldValue.of(String.valueOf(id)))
                .toList();
        return Query.of(q -> q.terms(t -> t.field(field).terms(tv -> tv.value(fieldValues))));
    }

    /**
     * 追加数值 range 过滤（上下限均可空，均空不加筛选；上限小于下限时忽略该条件防构造矛盾查询）
     *
     * <p>通俗地说：加一条数值区间门槛，比如字数要在 500 到 2000 之间。上下限可以只给一头；
     * 两头都没给就不加这条；上限反而小于下限这种矛盾区间直接忽略，不发给 ES。</p>
     *
     * @param filters 过滤器列表（就地追加）
     * @param field   数值字段名
     * @param min     下限（可空）
     * @param max     上限（可空）
     */
    private void addNumberRangeFilter(List<Query> filters, String field, Long min, Long max) {
        if (min == null && max == null) {
            return;
        }
        // 上限小于下限：矛盾区间忽略（ai 提取侧已校验倒置，此处双保险）
        if (min != null && max != null && min > max) {
            return;
        }
        filters.add(RangeQuery.of(r -> r.number(n -> {
            n.field(field);
            if (min != null) {
                n.gte(min.doubleValue());
            }
            if (max != null) {
                n.lte(max.doubleValue());
            }
            return n;
        }))._toQuery());
    }

    /**
     * 追加日期 range 过滤（ISO-8601 字符串解析；解析失败该条件忽略不报错——提取侧已校验格式）
     *
     * <p>通俗地说：与数值区间同理，只是条件换成时间。先把请求里的时间字符串解析成标准时间
     * （解析不了就当没这条条件），再生成 ES 的日期区间过滤。</p>
     *
     * @param filters 过滤器列表（就地追加）
     * @param field   date 字段名
     * @param start   起始时间（ISO-8601 字符串，可空）
     * @param end     结束时间（ISO-8601 字符串，可空）
     */
    private void addDateRangeFilter(List<Query> filters, String field, String start, String end) {
        LocalDateTime startDate = parseFlexibleDate(start);
        LocalDateTime endDate = parseFlexibleDate(end);
        if (startDate == null && endDate == null) {
            return;
        }
        filters.add(RangeQuery.of(r -> r.date(d -> {
            d.field(field);
            if (startDate != null) {
                d.gte(startDate.toString());
            }
            if (endDate != null) {
                d.lte(endDate.toString());
            }
            return d;
        }))._toQuery());
    }

    /**
     * 解析宽松日期字符串（yyyy-MM-dd 或完整 ISO 日期时间；解析失败返回 null 忽略该条件）
     *
     * <p>通俗地说：尽力把日期字符串读成时间值——先按「年月日时分秒」完整格式试，不行再按
     * 「年月日」试（当天零点起算），仍不行就记一条警告日志返回 null，由调用方忽略这个条件，
     * 而不是让查询报错。</p>
     *
     * @param value 日期字符串（可空）
     * @return LocalDateTime；缺失或非法返回 null
     */
    private LocalDateTime parseFlexibleDate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(value).atStartOfDay();
            } catch (DateTimeParseException e2) {
                log.warn("[条件版检索] 日期条件解析失败，忽略该条件：value={}", value);
                return null;
            }
        }
    }

    /**
     * 构造 terms 精确过滤（keyword 数组字段，如 tags）
     *
     * <p>通俗地说：加一条「这个字段的值必须命中列表里的某一项」的精确过滤，比如文章标签要
     * 包含用户指定的标签；空字符串先剔除再交给 ES。</p>
     *
     * @param field  字段名（索引常量）
     * @param values 过滤值列表
     * @return 查询 DSL
     */
    private Query termsFilter(String field, List<String> values) {
        List<FieldValue> fieldValues = values.stream()
                .filter(StringUtils::hasText)
                .map(v -> FieldValue.of(v.trim()))
                .toList();
        return Query.of(q -> q.terms(t -> t.field(field).terms(tv -> tv.value(fieldValues))));
    }

    /**
     * 以给定参照向量执行 KNN 检索（条件版 semantic 维度：queryText 向量替代源文章向量作参照）
     *
     * <p>通俗地说：拿着一个现成的向量（通常是查询文字实时向量化得到的）去 ES 里找「方向最接近」
     * 的文章——条件过滤、排除指定文章（自身与黑名单）都生效，返回的 score 就是与参照向量的
     * 相似度分。</p>
     *
     * @param queryVector      参照向量（已由调用方向量化）
     * @param excludeArticleId 需排除的自身文章 ID（可空）
     * @param filters          条件 filter 列表（含 visibility=PUBLIC 铁律）
     * @param topK             需要返回的条数
     * @return KNN 命中（含原始相似度分）
     * @throws BizException ES 不可用（SEARCH_ES_UNAVAILABLE）
     */
    private List<Hit<ArticleIndexDoc>> knnSearchByVector(List<Float> queryVector, Long excludeArticleId,
                                                         List<Long> excludeArticleIds,
                                                         List<Query> filters, int topK) {

        //        参照向量（queryText 向量化产物）
        //   ↓
        //        k = topK + 1（多取 1 条，防自身或排除项占用返回名额）
        //   ↓
        //        filter 条件准入 + mustNot 排除（单值自身 + 黑名单列表）
        //   ↓
        //        ES KNN 检索（vec_dense 字段，cosine 相似度）
        //   ↓
        //        返回命中列表（score 即与参照向量的相似度分）

        // 多取 1 条防自身占用名额（与 similar 同口径；excludeArticleId 为空时兜底）
        int k = topK + 1;
        SearchProperties.Knn knnConfig = properties.getKnn();
        int candidates = Math.max(knnConfig.getNumCandidates(), k);
        // 组装 filter + mustNot（排除自身与 similar 双保险口径一致；黑名单列表对称排除，板块四 4.1）
        BoolQuery.Builder boolBuilder = new BoolQuery.Builder().filter(filters);
        if (excludeArticleId != null) {
            boolBuilder.mustNot(termFilter(SearchFieldConstant.FIELD_ID, String.valueOf(excludeArticleId)));
        }
        if (excludeArticleIds != null && !excludeArticleIds.isEmpty()) {
            boolBuilder.mustNot(idTermsFilter(SearchFieldConstant.FIELD_ID, excludeArticleIds));
        }
        KnnSearch knn = KnnSearch.of(builder -> builder
                .field(SearchFieldConstant.FIELD_VEC_DENSE)
                .queryVector(queryVector)
                .k(k)
                .numCandidates(candidates)
                .filter(boolBuilder.build()._toQuery()));
        SearchRequest request = new SearchRequest.Builder()
                .index(properties.getIndex().getArticle())
                .size(k)
                .knn(knn)
                .build();
        try {
            SearchResponse<ArticleIndexDoc> response = elasticsearchClient.search(request, ArticleIndexDoc.class);
            return response.hits().hits();
        } catch (Exception e) {
            log.error("[条件版检索] KNN 检索失败：excludeArticleId={}", excludeArticleId, e);
            throw new BizException(ErrorCode.SEARCH_ES_UNAVAILABLE, "搜索服务暂不可用，请稍后重试", e);
        }
    }

    /**
     * 纯条件过滤检索（queryText 为空 / embedding 未就绪时退化路径：bool filter + sortHint 排序）
     *
     * <p>通俗地说：没有向量可用时的退化检索——只按条件筛文章（全部条件必须满足），再按排序意图
     * 指定字段倒序取前 N 条：要「最新」按更新时间排；其余意图没有可比的相似度分，统一按热度排。</p>
     *
     * @param excludeArticleId 需排除的自身文章 ID（可空）
     * @param filters          条件 filter 列表（含 visibility=PUBLIC 铁律）
     * @param topK             需要返回的条数
     * @param sortHint         排序意图（newest→updateTime 降序，其余→heat 降序）
     * @return 命中列表（score 为 ES 过滤打分恒 0，排序完全由 sortHint 承担）
     * @throws BizException ES 不可用（SEARCH_ES_UNAVAILABLE）
     */
    private List<Hit<ArticleIndexDoc>> filterSearch(Long excludeArticleId, List<Long> excludeArticleIds,
                                                    List<Query> filters, int topK, String sortHint) {

        //        组装纯过滤查询（全部条件必须满足 + 单值自身与黑名单列表排除）
        //   ↓
        //        选排序字段（newest → 更新时间降序；其余意图无相似度可比 → 热度降序）
        //   ↓
        //        ES 检索取前 topK 条（score 恒 0，排序完全由字段排序决定）

        BoolQuery.Builder boolBuilder = new BoolQuery.Builder().filter(filters);
        if (excludeArticleId != null) {
            boolBuilder.mustNot(termFilter(SearchFieldConstant.FIELD_ID, String.valueOf(excludeArticleId)));
        }
        // 黑名单列表排除（板块四 4.1：与单值排除对称，AI 板块十四 6.4 排除已读用）
        if (excludeArticleIds != null && !excludeArticleIds.isEmpty()) {
            boolBuilder.mustNot(idTermsFilter(SearchFieldConstant.FIELD_ID, excludeArticleIds));
        }
        // 纯条件路径排序：newest 按 updateTime 降序；related/heat/similarity-first 无向量分可比，统一按热度降序
        String sortField = SORT_NEWEST.equals(sortHint)
                ? SearchFieldConstant.FIELD_UPDATE_TIME : SearchFieldConstant.FIELD_HEAT;
        SearchRequest request = new SearchRequest.Builder()
                .index(properties.getIndex().getArticle())
                .size(topK)
                .query(boolBuilder.build()._toQuery())
                .sort(List.of(SortOptions.of(s -> s.field(f -> f.field(sortField).order(SortOrder.Desc)))))
                .build();
        try {
            SearchResponse<ArticleIndexDoc> response = elasticsearchClient.search(request, ArticleIndexDoc.class);
            return response.hits().hits();
        } catch (Exception e) {
            log.error("[条件版检索] 过滤检索失败：excludeArticleId={}", excludeArticleId, e);
            throw new BizException(ErrorCode.SEARCH_ES_UNAVAILABLE, "搜索服务暂不可用，请稍后重试", e);
        }
    }

    /**
     * 条件版融合评分、排序与截断（板块三 3.1：sortHint 决定排序与融合权重语义）
     *
     * <p>通俗地说：把检索到的候选文章按用户想要的排序方式排出最终名单——「最像」按分数排
     * （similarity-first 用纯向量分、related 用三路融合分），「最新」按更新时间排，「最热」按
     * 热度排；向量路径先砍掉相似度低于阈值的低质候选；自身与黑名单文章在这里再拦一遍（双保险）。</p>
     *
     * <p>【语义口径】similarity-first → 融合权重置向量 1 其余归零（纯向量相似排序）；
     * newest → 按更新时间降序；heat → 按热度降序；related → 三路融合权重归一化排序。
     * 相似度阈值过滤仅在向量路径生效（纯条件路径 score 无相似语义）。</p>
     *
     * @param hits             检索命中
     * @param excludeArticleId 需排除的自身文章 ID（可空，双保险二次排除）
     * @param topK             最终返回条数
     * @param sortHint         归一化后的排序意图
     * @param vectorPath       是否向量路径（决定阈值过滤与相似度来源）
     * @return 相似文章列表（按 sortHint 对应排序）
     */
    private List<SimilarArticleVO> fuseBySortHint(List<Hit<ArticleIndexDoc>> hits, Long excludeArticleId,
                                                  List<Long> excludeArticleIds, int topK, String sortHint,
                                                  boolean vectorPath) {

        //        检索命中
        //   ↓
        //        逐条校验（空文档 / 主键非法 / 自身 / 黑名单，双保险再拦一遍）
        //   ↓
        //        相似度阈值过滤（仅向量路径；纯条件路径 score 无相似语义不过滤）
        //   ↓
        //        融合打分（similarity-first 纯向量分；related 三路加权；newest/heat 分恒 0）
        //   ↓
        //        按 sortHint 排序（newest → 更新时间 / heat → 热度 / 其余 → 分数）降序
        //   ↓
        //        截断 topK

        // 黑名单集合化（双保险二次排除用；null 安全为空集）
        Set<Long> excludedSet = excludeArticleIds == null ? Set.of() : new HashSet<>(excludeArticleIds);
        // 相似度阈值（仅向量路径过滤低质候选；批次七默认 0.4）
        double threshold = vectorPath ? properties.getKnn().getSimilarityThreshold() : 0.0D;
        // 融合权重：similarity-first 置纯向量（vectorWeight=1 其余归零），其余按配置归一化
        double[] weights = SORT_SIMILARITY_FIRST.equals(sortHint)
                ? new double[]{1.0D, 0.0D, 0.0D} : normalizeWeights(properties.getKnn());
        List<SimilarArticleVO> candidates = new ArrayList<>();
        for (Hit<ArticleIndexDoc> hit : hits) {
            ArticleIndexDoc doc = hit.source();
            // 文档为空跳过
            if (doc == null) {
                continue;
            }
            Long candidateId = parseId(doc.getId());
            // ID 解析失败跳过；自身排除双保险（filter 已排除，防脏数据）；黑名单双保险（板块四 4.1）
            if (candidateId == null || (excludeArticleId != null && excludeArticleId.equals(candidateId))
                    || excludedSet.contains(candidateId)) {
                continue;
            }
            // 向量相似度（KNN 分；纯条件路径 score 无相似语义按 0 处理）+ 阈值过滤（仅向量路径）
            double similarity = vectorPath && hit.score() != null ? hit.score() : 0.0D;
            if (vectorPath && similarity < threshold) {
                continue;
            }
            // 融合分（向量路径按权重融合；纯条件路径融合分恒 0，排序由 sortHint 承担）
            double fused = similarity * weights[0]
                    + tagSimilarity(List.of(), doc.getTags()) * weights[1]
                    + normalizeHeat(doc.getHeat()) * weights[2];
            candidates.add(toVo(candidateId, doc, fused));
        }
        // 按 sortHint 排序：newest 按 updateTime 降序、heat 按热度降序、其余按融合分降序
        Comparator<SimilarArticleVO> comparator;
        if (SORT_NEWEST.equals(sortHint)) {
            comparator = Comparator.comparing(SimilarArticleVO::getUpdateTime,
                            Comparator.nullsLast(Comparator.naturalOrder())).reversed();
        } else if (SORT_HEAT.equals(sortHint)) {
            comparator = Comparator.comparing(SimilarArticleVO::getHeat,
                            Comparator.nullsLast(Comparator.naturalOrder())).reversed();
        } else {
            comparator = Comparator.comparing(SimilarArticleVO::getScore).reversed();
        }
        candidates.sort(comparator);
        if (candidates.size() > topK) {
            return new ArrayList<>(candidates.subList(0, topK));
        }
        return candidates;
    }

    /**
     * 条件版命中转 VO（含 authorId 与排序透传字段）
     *
     * <p>通俗地说：把 ES 索引文档里前端展示需要的字段（标题/摘要/作者/热度/更新时间）搬到
     * 返回对象上，主键字符串转回数字 ID；score 填这条候选参与排序用的分数。</p>
     *
     * @param articleId 文章 ID
     * @param doc       索引文档源数据
     * @param score     融合分
     * @return 相似文章 VO
     */
    private SimilarArticleVO toVo(Long articleId, ArticleIndexDoc doc, double score) {
        SimilarArticleVO vo = new SimilarArticleVO();
        vo.setArticleId(articleId);
        vo.setAuthorId(parseId(doc.getAuthorId()));
        vo.setTitle(doc.getTitle());
        vo.setSummary(doc.getSummary());
        vo.setAuthorName(doc.getAuthorName());
        vo.setScore(score);
        vo.setHeat(doc.getHeat());
        vo.setUpdateTime(doc.getUpdateTime());
        return vo;
    }

    /**
     * 解析并约束返回条数。
     *
     * <p>通俗地说：把调用方要的返回条数管起来——没填用配置默认值，要得太多按配置上限砍，
     * 填 0 或负数直接报错，保证后续 ES 查询拿到的条数永远合法。</p>
     *
     * @param topK 调用方传入条数（可空）
     * @return 合法返回条数（缺省 knn.top-k，上限 knn.max-top-k）
     */
    private int resolveTopK(Integer topK) {
        SearchProperties.Knn knn = properties.getKnn();
        // 配置异常保护：上限至少 1
        int max = Math.max(1, knn.getMaxTopK());
        int requested = topK == null ? knn.getTopK() : topK;
        // 非正数直接拒绝（避免 KNN k<=0 触发 ES 报错）
        if (requested < 1) {
            throw new BizException(ErrorCode.SEARCH_PARAM_INVALID, "topK 必须为大于 0 的整数");
        }
        return Math.min(requested, max);
    }

    /**
     * 回查源文章索引文档。
     *
     * <p>通俗地说：按 ID 去 ES 把源文章的完整索引文档取出来（相似推荐的起点，里面带文章自己的
     * 向量和标签）。文章不存在或没入索引报「源文章不存在」；ES 连不上报「服务暂不可用」——
     * 两种失败分开，调用方能分别处理。</p>
     *
     * @param articleId 文章 ID
     * @return 索引文档（含 vec_dense/tags）
     * @throws BizException 文档不存在（SEARCH_SOURCE_NOT_FOUND）或 ES 不可用（SEARCH_ES_UNAVAILABLE）
     */
    private ArticleIndexDoc fetchSource(Long articleId) {
        String articleIndex = properties.getIndex().getArticle();
        String id = String.valueOf(articleId);
        try {
            GetResponse<ArticleIndexDoc> response =
                    elasticsearchClient.get(g -> g.index(articleIndex).id(id), ArticleIndexDoc.class);
            // 文档不存在：源文章未发布/已下架/从未入索引
            if (!response.found() || response.source() == null) {
                throw new BizException(ErrorCode.SEARCH_SOURCE_NOT_FOUND, "源文章不存在或未入索引：" + articleId);
            }
            return response.source();
        } catch (BizException e) {
            // 业务性不存在原样抛出（调用方按 100003 处理）
            throw e;
        } catch (Exception e) {
            // ES/网络异常：相似推荐无降级数据源，按服务不可用返回
            log.error("[相似推荐] 源文章回查失败：articleId={}", articleId, e);
            throw new BizException(ErrorCode.SEARCH_ES_UNAVAILABLE, "搜索服务暂不可用，请稍后重试", e);
        }
    }

    /**
     * 以源文章向量执行 KNN 检索。
     *
     * 用源文章的向量，在 ES 里做 KNN 近邻搜索，过滤掉自己和非公开文章，返回最相似的 N 篇。
     *
     * @param source 源文章索引文档（必须已有向量）
     * @param topK   需要返回的条数
     * @return KNN 命中（含原始相似度分）
     * @throws BizException ES 不可用（SEARCH_ES_UNAVAILABLE）
     */
    private List<Hit<ArticleIndexDoc>> knnSearch(ArticleIndexDoc source, int topK) {

//        源文章向量
//   ↓
//        +1 防止自身占用 topK
//   ↓
//        visibility=PUBLIC
//   ↓
//        mustNot 自身 ID
//   ↓
//        ES KNN 检索
//   ↓
//        返回 topK+1 条
//   ↓
//        外层融合分排序
//   ↓
//        最终截断为 topK

        //① 读取 KNN 配置
        SearchProperties.Knn knnConfig = properties.getKnn();

        //② 计算实际查询的 k 值（多取 1 条）
        // 多取 1 条：即使自身未被 filter 排除也能凑满 topK，最终仍按融合分截断
        int k = topK + 1;

        //③ 计算 numCandidates（候选集大小）
        // numCandidates 必须 >= k
        int candidates = Math.max(knnConfig.getNumCandidates(), k);

        //④ 构造过滤条件（filter + mustNot）
        // 强制 visibility=PUBLIC（C3）+ must_not 排除自身
        Query filter = new BoolQuery.Builder()
                .filter(termFilter(SearchFieldConstant.FIELD_VISIBILITY, SearchGateConstant.VISIBILITY_PUBLIC))
                .mustNot(termFilter(SearchFieldConstant.FIELD_ID, source.getId()))
                .build()._toQuery();

        //⑤ 构造 KNN 查询
        KnnSearch knn = KnnSearch.of(builder -> builder                // 使用 Java Client 构建器创建 KNN 查询对象
                .field(SearchFieldConstant.FIELD_VEC_DENSE)                   // 指定 ES 中存储稠密向量的字段名（如 vec_dense）
                .queryVector(source.getVecDense())                            // 传入源文章的向量，作为相似度检索的"参照物"
                .k(k)                                                         // 设置返回最相似的 k 条结果（topK+1，多取一条防止自身占用名额）
                .numCandidates(candidates)                                    // 设置 HNSW 图中探查的候选集大小（≥k），控制召回质量与性能平衡
                .filter(filter));                                             // 设置前置过滤条件：只搜公开文章 + 排除自身，减少无效向量计算

        //⑥ 构造 SearchRequest
        SearchRequest request = new SearchRequest.Builder()
                .index(properties.getIndex().getArticle())
                .size(k)
                .knn(knn)
                .build();

        try {
            //⑦ 执行 ES 检索
            SearchResponse<ArticleIndexDoc> response =
                    elasticsearchClient.search(request, ArticleIndexDoc.class);
            return response.hits().hits();
        } catch (Exception e) {
            //⑧ 异常处理（ES 不可用时）
            log.error("[相似推荐] KNN 检索失败：articleId={}", source.getId(), e);
            throw new BizException(ErrorCode.SEARCH_ES_UNAVAILABLE, "搜索服务暂不可用，请稍后重试", e);
        }
    }

    /**
     * 融合评分并过滤、排序、截断。
     *
     * 把 KNN 召回的候选文章，用“向量相似度 + 标签重合度 + 热度”三路加权融合打分，过滤低质量结果，最终按融合分取 topK。
     *
     * @param hits   KNN 命中
     * @param source 源文章（标签重合度基准）
     * @param topK   最终返回条数
     * @return 相似文章列表（按融合分降序）
     */
    private List<SimilarArticleVO> fuseAndLimit(List<Hit<ArticleIndexDoc>> hits, ArticleIndexDoc source, int topK) {

//        KNN 原始命中
//   ↓
//        逐条校验（空值 / 自身 / 阈值）
//   ↓
//        三路融合打分
//   ↓
//        封装 VO
//   ↓
//        按融合分降序排序
//   ↓
//        截断到 topK

        //① 读取配置 & 权重归一化
        SearchProperties.Knn knnConfig = properties.getKnn();
        // 原始相似度阈值（cosine 分，批次七默认 0.4；配置为 0.0 时表示不过滤）
        double threshold = knnConfig.getSimilarityThreshold();
        // 权重按总和归一化，配置允许任意比例（全 0 时退化为纯向量分）
        double[] weights = normalizeWeights(knnConfig);

        //② 遍历 KNN 命中结果
        List<SimilarArticleVO> candidates = new ArrayList<>();
        for (Hit<ArticleIndexDoc> hit : hits) {

            //③ 基础校验（逐条过滤）
            //3.1 文档为空跳过
            ArticleIndexDoc doc = hit.source();
            if (doc == null) {
                continue;
            }
            Long candidateId = parseId(doc.getId());
            //3.2 ID 解析 & 自身排除（双保险）
            // 主键异常跳过；仍是自身（filter 已排除，双保险）也跳过
            if (candidateId == null || (source.getId() != null && source.getId().equals(doc.getId()))) {
                continue;
            }

            //④ 向量相似度阈值过滤
            // 原始向量相似度（KNN 分；缺失按 0 处理）
            double similarity = hit.score() == null ? 0.0 : hit.score();
            if (similarity < threshold) {
                continue;
            }

            ///⑤ 三路融合打分（核心逻辑）
            // 融合公式：融合分 = 向量相似度 × w₀ + 标签重合度 × w₁ + 热度归一值 × w₂
            // 融合分 = 向量相似×权重 + 标签重合度×权重 + 热度归一值×权重
            double fused = similarity * weights[0]
                    + tagSimilarity(source.getTags(), doc.getTags()) * weights[1]
                    + normalizeHeat(doc.getHeat()) * weights[2];

            //⑥ 封装 VO
            SimilarArticleVO vo = new SimilarArticleVO();
            vo.setArticleId(candidateId);
            vo.setAuthorId(parseId(doc.getAuthorId()));
            vo.setTitle(doc.getTitle());
            vo.setSummary(doc.getSummary());
            vo.setAuthorName(doc.getAuthorName());
            vo.setScore(fused);
            candidates.add(vo);
        }

        //⑦ 排序 & 截断
        // 按融合分降序，截断到 topK（KNN 多取 1 条仅用于兜底自身，不改变上限语义）
        candidates.sort(Comparator.comparing(SimilarArticleVO::getScore).reversed());
        if (candidates.size() > topK) {
            return new ArrayList<>(candidates.subList(0, topK));
        }
        return candidates;
    }

    /**
     * 归一化融合权重。
     *
     * <p>通俗地说：把配置里三个权重（向量/标签/热度）折算成总和为 1 的比例，这样配置写 3:2:1
     * 或 30:20:10 打分口径都一致；配置全填 0 或负数这种异常情况按「只看向量相似度」兜底，
     * 功能不中断。</p>
     *
     * @param knnConfig KNN 配置
     * @return 长度 3 的权重数组（向量/标签/热度），总和为 1；配置全 0 时退化为纯向量
     */
    private double[] normalizeWeights(SearchProperties.Knn knnConfig) {
        double vector = Math.max(0.0, knnConfig.getVectorWeight());
        double tag = Math.max(0.0, knnConfig.getTagWeight());
        double heat = Math.max(0.0, knnConfig.getHeatWeight());
        double sum = vector + tag + heat;
        // 配置异常（全 0/负数）：只按向量相似度排序，保证结果仍可用
        if (sum <= 0.0) {
            return new double[]{1.0, 0.0, 0.0};
        }
        return new double[]{vector / sum, tag / sum, heat / sum};
    }

    /**
     * 标签重合度（Jaccard：交集大小 / 并集大小）。
     *
     * <p>通俗地说：数两篇文章标签的重合程度——交集标签数除以并集标签数（Jaccard）：
     * 标签完全一样得 1，一个都不重合得 0，任一方没标签也是 0。</p>
     *
     * @param sourceTags    源文章标签
     * @param candidateTags 候选文章标签
     * @return 0~1 的重合度；任一方为空返回 0
     */
    private double tagSimilarity(List<String> sourceTags, List<String> candidateTags) {
        if (sourceTags == null || sourceTags.isEmpty() || candidateTags == null || candidateTags.isEmpty()) {
            return 0.0;
        }
        Set<String> sourceSet = new HashSet<>(sourceTags);
        Set<String> candidateSet = new HashSet<>(candidateTags);
        // 交集：保留双方都有的标签
        Set<String> intersection = new HashSet<>(sourceSet);
        intersection.retainAll(candidateSet);
        if (intersection.isEmpty()) {
            return 0.0;
        }
        // 并集大小 = 双方大小之和 - 交集大小（免去再构造并集集合）
        int unionSize = sourceSet.size() + candidateSet.size() - intersection.size();
        return unionSize <= 0 ? 0.0 : (double) intersection.size() / unionSize;
    }

    /**
     * 热度归一化到 0~1：log1p 压缩后取 x/(1+x)，防超大热度直接压过语义相似度。
     *
     * <p>通俗地说：把热度（可能很大的数字）折算到 0~1 之间，防止热度特别高的文章在融合分里
     * 盖过内容更相似的文章。先取对数再折算：热度越大压得越低，但永远大于 0。</p>
     *
     * @param heat 互动热度（可空）
     * @return 0~1 归一值
     */
    private double normalizeHeat(Double heat) {
        if (heat == null || heat <= 0) {
            return 0.0;
        }
        double compressed = Math.log1p(heat);
        return compressed / (1.0 + compressed);
    }

    /**
     * 构造 term 精确过滤。
     *
     * <p>通俗地说：造一条「该字段的值必须完全等于给定值」的精确过滤条件，例如
     * visibility=PUBLIC，是 ES 过滤查询的基础单元。</p>
     *
     * @param field 字段名（索引常量）
     * @param value 过滤值
     * @return 查询 DSL
     */
    private Query termFilter(String field, String value) {
        return TermQuery.of(t -> t.field(field).value(FieldValue.of(value)))._toQuery();
    }

    /**
     * 文档主键字符串转 Long（非法值告警并跳过该条）。
     *
     * <p>通俗地说：把 ES 文档主键（字符串）转成数字 ID；主键内容异常转不动时记一条警告返回
     * null，由调用方跳过这条数据，不让单条脏数据中断整个查询。</p>
     *
     * @param value 文档主键
     * @return Long 主键；缺失或非法返回 null
     */
    private Long parseId(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            log.warn("[相似推荐] 索引文档主键非法，跳过该条：value={}", value);
            return null;
        }
    }
}
