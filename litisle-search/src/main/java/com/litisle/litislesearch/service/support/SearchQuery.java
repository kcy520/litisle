package com.litisle.litislesearch.service.support;

import com.litisle.litislesearch.enums.SearchSortEnum;
import com.litisle.litislesearch.enums.SearchTypeEnum;

import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 归一化后的检索参数载体（批次四 4.1）。
 *
 * 【职责边界】由 {@link SearchQueryPreparer} 完成参数校验与归一化后构造，
 * 供 ES 查询构建（{@link EsSearchQueryBuilder}）、兜底与降级链路（{@link SearchFallbackService}）、
 * 结果转换（{@link SearchResultConverter}）统一复用；本类只承载参数，不含任何业务行为。
 *
 * @author litisle
 */
@Getter
public class SearchQuery {

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

    /**
     * 构造归一化检索参数（仅由 SearchQueryPreparer 在校验通过后调用）。
     *
     * @param keyword        归一化查询词（未转义）
     * @param escapedKeyword 转义后的查询词（进 multi_match DSL）
     * @param type           检索类型
     * @param sort           排序方式
     * @param pageNum        页码（从 1 开始）
     * @param pageSize       每页条数
     * @param from           from 偏移
     * @param publishBegin   发布时间范围起点（可空）
     * @param publishEnd     发布时间范围终点（可空）
     * @param tag            标签筛选（可空）
     * @param category       分类筛选（可空）
     * @param zone           分区筛选（可空）
     */
    public SearchQuery(String keyword, String escapedKeyword, SearchTypeEnum type, SearchSortEnum sort,
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
