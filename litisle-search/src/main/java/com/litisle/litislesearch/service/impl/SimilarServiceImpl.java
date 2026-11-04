package com.litisle.litislesearch.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TermQuery;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litisleapi.vo.search.SimilarArticleVO;
import com.litisle.litislesearch.config.SearchProperties;
import com.litisle.litislesearch.constant.SearchBizConstant;
import com.litisle.litislesearch.domain.entity.ArticleIndexDoc;
import com.litisle.litislesearch.service.SimilarService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 相似文章推荐实现（批次七 7.1）。
 *
 * 【流程】源文章回查（vec_dense + tags）→ KNN（num_candidates、cosine）
 * → 融合评分（向量相似 + 标签重合度 + 行为热度，权重外置）
 * → 相似度阈值过滤 → 按融合分降序截断 topK。
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

    /** ES 客户端（源文章回查 + KNN 检索） */
    private final ElasticsearchClient elasticsearchClient;

    /** search 配置（索引名 + KNN/融合权重参数） */
    private final SearchProperties properties;

    /**
     * 查询相似文章。
     *
     * @param articleId 源文章 ID
     * @param topK      返回条数（可空）
     * @return 相似文章列表（按融合分降序）
     */
    @Override
    public List<SimilarArticleVO> similar(Long articleId, Integer topK) {
        // 步骤1：参数校验（文章 ID 必须为正；topK 越界由配置上限截断）
        if (articleId == null || articleId <= 0) {
            throw new BizException(ErrorCode.SEARCH_PARAM_INVALID, "articleId 必须为正整数");
        }
        int limit = resolveTopK(topK);

        // 步骤2：回查源文章（不存在/已下架返回 SEARCH_SOURCE_NOT_FOUND，由调用方决策）
        ArticleIndexDoc source = fetchSource(articleId);
        // 防御：写入侧只入 PUBLIC，查询侧再兜底，防脏数据把私密文章当推荐源
        if (!SearchBizConstant.VISIBILITY_PUBLIC.equals(source.getVisibility())) {
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
     * 解析并约束返回条数。
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
     * @param source 源文章索引文档（必须已有向量）
     * @param topK   需要返回的条数
     * @return KNN 命中（含原始相似度分）
     * @throws BizException ES 不可用（SEARCH_ES_UNAVAILABLE）
     */
    private List<Hit<ArticleIndexDoc>> knnSearch(ArticleIndexDoc source, int topK) {
        SearchProperties.Knn knnConfig = properties.getKnn();
        // 多取 1 条：即使自身未被 filter 排除也能凑满 topK，最终仍按融合分截断
        int k = topK + 1;
        // numCandidates 必须 >= k
        int candidates = Math.max(knnConfig.getNumCandidates(), k);
        // 强制 visibility=PUBLIC（C3）+ must_not 排除自身
        Query filter = new BoolQuery.Builder()
                .filter(termFilter(SearchBizConstant.FIELD_VISIBILITY, SearchBizConstant.VISIBILITY_PUBLIC))
                .mustNot(termFilter(SearchBizConstant.FIELD_ID, source.getId()))
                .build()._toQuery();
        KnnSearch knn = KnnSearch.of(builder -> builder
                .field(SearchBizConstant.FIELD_VEC_DENSE)
                .queryVector(source.getVecDense())
                .k(k)
                .numCandidates(candidates)
                .filter(filter));
        SearchRequest request = new SearchRequest.Builder()
                .index(properties.getIndex().getArticle())
                .size(k)
                .knn(knn)
                .build();
        try {
            SearchResponse<ArticleIndexDoc> response =
                    elasticsearchClient.search(request, ArticleIndexDoc.class);
            return response.hits().hits();
        } catch (Exception e) {
            log.error("[相似推荐] KNN 检索失败：articleId={}", source.getId(), e);
            throw new BizException(ErrorCode.SEARCH_ES_UNAVAILABLE, "搜索服务暂不可用，请稍后重试", e);
        }
    }

    /**
     * 融合评分并过滤、排序、截断。
     *
     * @param hits   KNN 命中
     * @param source 源文章（标签重合度基准）
     * @param topK   最终返回条数
     * @return 相似文章列表（按融合分降序）
     */
    private List<SimilarArticleVO> fuseAndLimit(List<Hit<ArticleIndexDoc>> hits, ArticleIndexDoc source, int topK) {
        SearchProperties.Knn knnConfig = properties.getKnn();
        // 原始相似度阈值（cosine 分，0.0 表示不过滤负相似）
        double threshold = knnConfig.getSimilarityThreshold();
        // 权重按总和归一化，配置允许任意比例（全 0 时退化为纯向量分）
        double[] weights = normalizeWeights(knnConfig);
        List<SimilarArticleVO> candidates = new ArrayList<>();
        for (Hit<ArticleIndexDoc> hit : hits) {
            ArticleIndexDoc doc = hit.source();
            if (doc == null) {
                continue;
            }
            Long candidateId = parseId(doc.getId());
            // 主键异常跳过；仍是自身（filter 已排除，双保险）也跳过
            if (candidateId == null || (source.getId() != null && source.getId().equals(doc.getId()))) {
                continue;
            }
            // 原始向量相似度（KNN 分；缺失按 0 处理）
            double similarity = hit.score() == null ? 0.0 : hit.score();
            if (similarity < threshold) {
                continue;
            }
            // 融合分 = 向量相似×权重 + 标签重合度×权重 + 热度归一值×权重
            double fused = similarity * weights[0]
                    + tagSimilarity(source.getTags(), doc.getTags()) * weights[1]
                    + normalizeHeat(doc.getHeat()) * weights[2];
            SimilarArticleVO vo = new SimilarArticleVO();
            vo.setArticleId(candidateId);
            vo.setTitle(doc.getTitle());
            vo.setSummary(doc.getSummary());
            vo.setAuthorName(doc.getAuthorName());
            vo.setScore(fused);
            candidates.add(vo);
        }
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
