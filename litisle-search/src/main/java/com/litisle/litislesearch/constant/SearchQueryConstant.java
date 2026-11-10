package com.litisle.litislesearch.constant;

/**
 * search 模块检索行为常量（批次四 4.1 / 4.2 收口）：
 * 覆盖检索参数默认值、结果来源标记、高亮规格、发布时间筛选格式。
 *
 * 说明：这些值是接口契约与缓存策略的一部分（来源标记影响热点缓存写入门槛），不适合外置 yml，统一收口
 */
public final class SearchQueryConstant {

    /** 工具类禁止实例化 */
    private SearchQueryConstant() {
    }

    // ==================== 检索参数默认值 ====================

    /**
     * 查询词最大长度（字符数，批次一 1.2 定版）：
     * search_history.keyword 列长度（varchar(64)）与本常量同源，
     * 批次四参数校验落地时按本常量拒绝超长查询词（SEARCH_PARAM_INVALID）
     */
    public static final int SEARCH_KEYWORD_MAX_LENGTH = 64;

    /** 检索默认页码（请求未传 pageNum 时从第一页开始） */
    public static final int SEARCH_DEFAULT_PAGE_NUM = 1;

    /** 检索默认每页条数（请求未传 pageSize 时使用，且不超过 page.max-page-size） */
    public static final int SEARCH_DEFAULT_PAGE_SIZE = 10;

    // ==================== 结果来源标记（与 SearchResultVO.source 契约一致） ====================

    /** 结果来源标记：常规 ES 检索命中（可写入热点查询缓存） */
    public static final String SEARCH_SOURCE_ES = "ES";

    /**
     * 结果来源标记：空结果兜底（查询词实时 Embedding + KNN，或二级 HEAT 热门列表）：
     * 兜底结果不是真实命中，禁止写入热点查询缓存（防把兜底当真命中）
     */
    public static final String SEARCH_SOURCE_FALLBACK = "fallback";

    /**
     * 结果来源标记：ES 整体不可用时的热门列表降级（批次A A3 定案 / 批次八 8.1）：
     * 数据来自 content 热门榜（与 /article/hot 同源），与查询词无关；
     * 禁止写入热点查询缓存——按查询键缓存会把同一份热门复制到每个关键词下白占内存，
     * 且 ES 恢复后缓存 TTL 内仍会返回降级结果，掩盖服务已恢复的事实
     */
    public static final String SEARCH_SOURCE_DEGRADED = "DEGRADED";

    // ==================== 高亮规格 ====================

    /** 高亮片段目标字符数（单片段，前端摘要卡片展示足够） */
    public static final int HIGHLIGHT_FRAGMENT_SIZE = 80;

    /** 高亮片段数量（每字段只取 1 段，保持 VO 单 highlight 字段语义） */
    public static final int HIGHLIGHT_FRAGMENT_COUNT = 1;

    // ==================== 发布时间筛选格式 ====================

    /** 发布时间筛选支持的第一格式：纯日期（yyyy-MM-dd） */
    public static final String PUBLISH_TIME_DATE_PATTERN = "yyyy-MM-dd";

    /** 纯日期字符串长度（用于区分「纯日期」与「日期时间」两种输入格式） */
    public static final int PUBLISH_TIME_DATE_LENGTH = 10;

    /** 发布时间筛选支持的第二格式（第一批为 ISO-8601，另兼容「yyyy-MM-dd HH:mm:ss」） */
    public static final String PUBLISH_TIME_DATETIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
}
