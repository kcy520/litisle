package com.litisle.litislesearch.service;

import com.litisle.litisleapi.dto.search.SimilarConditionRequestDTO;
import com.litisle.litisleapi.vo.search.SimilarArticleVO;

import java.util.List;

/**
 * 相似文章推荐服务（批次七 7.1 KNN 相似推荐 + 板块三 3.1 条件版相似查询）。
 *
 * 【职责】以源文章的 vec_dense 做 KNN 检索，融合标签重合度与行为热度后返回相似文章列表；
 * REST（GET /api/search/similar/{articleId}）与 Feign（GET /api/feign/search/similar）共用；
 * 条件版按维度注册表 match 类型路由四类查询构造（板块三 3.1），ai 条件提取管道消费。
 *
 * @author litisle
 */
public interface SimilarService {

    /**
     * 查询相似文章。
     *
     * <p>铁律（批次C C3）：KNN 检索强制 filter visibility=PUBLIC，私密/草稿/下架内容永不出现。</p>
     *
     * @param articleId 源文章 ID（必须已入 article_idx 且存在向量）
     * @param topK      返回条数（可空；缺省取 knn.top-k，超过 knn.max-top-k 截断）
     * @return 相似文章列表（按融合分降序）；源文章无向量时返回空列表
     */
    List<SimilarArticleVO> similar(Long articleId, Integer topK);

    /**
     * 条件版相似文章查询（板块三 3.1：按维度注册表 match 类型路由四类查询构造）。
     *
     * <p>【路由口径】filter 词表型 → term filter（aiLabels 各键，默认 ai_labels.{key}，
     * 声明 esField 的写既有字段，注册表外 key 忽略）；range 数值型 → range filter
     * （wordCount/publishTime 绑定注册表 esField）；param 型 → 直接映射（topK 上限截断、
     * sortHint 排序与融合权重——similarity-first 时向量权重置 1 其余归零）；semantic →
     * queryText embed 后 knn 作参照向量（queryText 为空退化纯条件过滤检索）。
     * visibility=PUBLIC + 排除自身铁律不变；无命中返回空列表不报错。</p>
     *
     * @param dto 条件检索请求（各条件均可空）
     * @return 相似文章列表（按 sortHint 对应排序；无命中为空列表不报错）
     */
    List<SimilarArticleVO> similarByCondition(SimilarConditionRequestDTO dto);

    /**
     * 回查文章 AI 标签（板块十二 4.6：风格仿写把 ai_labels 风格标签从「筛选用」变「生成用」）。
     *
     * <p>【口径】读 article_idx 单篇 ai_labels 词表型维度映射；文章未入索引或未打标返回
     * 空 map 不报错（调用方按「无风格标签」降级）；ES 异常按 SEARCH_ES_UNAVAILABLE 上抛。</p>
     *
     * @param articleId 文章 ID
     * @return ai_labels 维度映射（key=维度名如 style，value=词表值）；未入索引/未打标为空 map
     */
    java.util.Map<String, String> getArticleAiLabels(Long articleId);
}
