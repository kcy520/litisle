package com.litisle.litisleapi.dto.search;

import lombok.Data;

import java.io.Serializable;

/**
 * 搜索请求 DTO（跨服务检索契约，search 服务消费）
 *
 * 【使用场景】
 * 业务方或 ai 服务经 SearchClient.search(SearchRequestDTO) 发起服务间检索（非降级链路，
 * ES 不可用降级见 Search-Architecture.md §八-5 批次A A3 定案：返回热门列表，不走本契约查 DB）
 *
 * 【设计要点】
 * - type/sort 用 String 而非枚举：SearchTypeEnum / SearchSortEnum 定义在 search 模块，
 *   litisle-api 不能反向依赖（会成环），由提供方解析与校验（非法值按 SEARCH_PARAM_INVALID 拒绝）
 * - 分页上限由提供方收口（超限按 SEARCH_PAGE_EXCEEDED 拒绝），契约层不做业务校验
 *
 * @author litisle
 */
@Data
public class SearchRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 搜索关键词（必填，空串/空白由提供方按 SEARCH_PARAM_INVALID 拒绝） */
    private String q;

    /** 检索类型（article/book/user，决定查哪个索引；缺省由提供方按 article 处理） */
    private String type;

    /** 排序方式（related 相关度默认 / newest 最新 / heat 热度，口径见 SearchSortEnum） */
    private String sort;

    /** 页码（从 1 开始，缺省 1；上限由提供方收口） */
    private Integer pageNum;

    /** 每页条数（上限由提供方收口，防深翻页打爆 ES） */
    private Integer pageSize;

    /** 标签筛选（可选，命中 article_idx.tags term 过滤） */
    private String tag;

    /** 分类筛选（可选，仅文章/书籍索引生效） */
    private String category;

    /** 分区筛选（可选，仅文章索引生效） */
    private String zone;

    /** 发布时间范围-起（可选，含边界，按 updateTime 过滤） */
    private String publishBegin;

    /** 发布时间范围-止（可选，含边界，按 updateTime 过滤） */
    private String publishEnd;
}
