package com.litisle.litisleapi.dto.search;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 条件版相似文章检索请求 DTO（search 板块三 3.1，ai 条件提取管道 → search 跨服务契约）
 *
 * <p>【口径】条件字段由 ai 侧按维度注册表提取后传入，search 按注册表 match 类型路由 ES 查询：
 * filter 词表型 → term filter（aiLabels 各键，默认写 ai_labels.{维度名}，声明 esField 的写既有字段）；
 * range 数值型 → range filter（wordCount 区间 / publishTime 时间区间）；param 型 → 直接映射
 * （topK 上限截断、sortHint 融合权重调整）；semantic 型 → queryText embed 后 knn 作参照向量。
 * visibility=PUBLIC + 排除自身铁律由 search 侧强制，调用方无需重复传。</p>
 *
 * <p>【失败语义】无命中返回空列表不报错；queryText 为空时退化纯条件过滤检索（按 sortHint 排序）。</p>
 *
 * @author litisle
 */
@Data
public class SimilarConditionRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 请求幂等键（ai 侧计量 requestId 原样传入，仅留痕排查用） */
    private String requestId;

    /** 语义检索文本（semantic 维度合并产物；为空=纯条件过滤检索） */
    private String queryText;

    /** 需排除的自身文章 ID（被浏览文章本身，可空） */
    private Long excludeArticleId;

    /** 指定作者 ID（「作者 X 的」类条件，经 user_idx 解析后传入，可空） */
    private Long authorId;

    /** 需排除的作者 ID 列表（「不要作者 Y 的」类条件，可空） */
    private List<Long> excludeAuthorIds;

    /**
     * 白名单文章 ID 列表（search 板块四 4.1：must terms filter，只在指定集合内检索——
     * AI 板块十一 3.2 收藏夹语义检索传用户收藏的文章 ID；空/null 不生效）
     */
    private List<Long> includeArticleIds;

    /**
     * 黑名单文章 ID 列表（search 板块四 4.1：must_not terms filter，与 excludeArticleId
     * 单值排除对称——AI 板块十四 6.4 排除已读用；空/null 不生效）
     */
    private List<Long> excludeArticleIds;

    /** 分类名（可空） */
    private String category;

    /** 标签列表（可空，取交集过滤） */
    private List<String> tags;

    /**
     * ai_labels 词表型维度条件（key=维度名如 style/depth/structure，value=词表值）；
     * search 侧按注册表路由：未声明 esField 的写 ai_labels.{key}，声明的写既有字段；注册表外 key 忽略
     */
    private Map<String, String> aiLabels;

    /** 正文字数下限（可空） */
    private Long wordCountMin;

    /** 正文字数上限（可空） */
    private Long wordCountMax;

    /** 发布时间起（ISO-8601 字符串，可空；解析失败该条件忽略） */
    private String publishTimeStart;

    /** 发布时间止（ISO-8601 字符串，可空） */
    private String publishTimeEnd;

    /** 返回条数上限（可空，缺省由提供方配置默认值，超上限截断） */
    private Integer topK;

    /**
     * 排序意图（可空默认 related）：related=按相关性融合分排序 / newest=最新发布优先 /
     * heat=热度优先 / similarity-first=纯向量相似优先（融合权重置向量 1 其余归零）
     */
    private String sortHint;
}
