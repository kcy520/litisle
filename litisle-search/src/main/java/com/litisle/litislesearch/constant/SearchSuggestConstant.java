package com.litisle.litislesearch.constant;

/**
 * search 模块搜索联想常量（批次六 6.1 + 企业优化清单 1.1/1.3 收口）：
 * 覆盖联想类型、completion 子字段路径、suggester 名称、联想风控阈值。
 *
 * 说明：suggester 名称是请求与响应解析的对齐依据，风控阈值是与内容服务限流矩阵对齐的契约值，
 * 不适合外置 yml，统一收口避免字符串硬编码
 */
public final class SearchSuggestConstant {

    /** 工具类禁止实例化 */
    private SearchSuggestConstant() {
    }

    // ==================== 联想类型 ====================

    /** 联想类型：书名（article_idx title.completion） */
    public static final String SUGGEST_TYPE_KEYWORD = "KEYWORD";

    /** 联想类型：作者（article_idx authorName.completion） */
    public static final String SUGGEST_TYPE_AUTHOR = "AUTHOR";

    /** 联想类型：标签（content 标签池前缀查询，不建索引） */
    public static final String SUGGEST_TYPE_TAG = "TAG";

    // ==================== completion 子字段路径 ====================

    /** completion 子字段路径分隔符（父字段名 + "." + 子字段名） */
    public static final String SUGGEST_COMPLETION_FIELD_SEPARATOR = ".";

    /** completion 子字段名（批次一 mapping 已定义 title/authorName 的 completion 子字段） */
    public static final String SUGGEST_COMPLETION_SUFFIX = "completion";

    /** 书名联想字段路径：title.completion */
    public static final String SUGGEST_FIELD_TITLE_COMPLETION =
            SearchFieldConstant.FIELD_TITLE + SUGGEST_COMPLETION_FIELD_SEPARATOR + SUGGEST_COMPLETION_SUFFIX;

    /** 作者联想字段路径：authorName.completion */
    public static final String SUGGEST_FIELD_AUTHOR_NAME_COMPLETION =
            SearchFieldConstant.FIELD_AUTHOR_NAME + SUGGEST_COMPLETION_FIELD_SEPARATOR + SUGGEST_COMPLETION_SUFFIX;

    // ==================== completion suggester 名称 ====================

    /** 书名 completion suggester 名称（响应按名称分组解析） */
    public static final String SUGGESTER_NAME_TITLE = "title_suggest";

    /** 作者 completion suggester 名称（响应按名称分组解析） */
    public static final String SUGGESTER_NAME_AUTHOR = "author_suggest";

    // ==================== 联想风控（企业优化清单 1.1 / 1.3） ====================

    /** 联想最小输入长度（字符）：单字/空串不触发联想，砍掉无效的 completion/标签池查询（1.1） */
    public static final int SUGGEST_MIN_KEYWORD_LENGTH = 2;

    /** 联想匿名限流窗口（秒）：与内容服务限流矩阵统一 60s（1.3） */
    public static final int SUGGEST_RATE_WINDOW_SECONDS = 60;

    /** 联想匿名限流阈值（次/窗口/IP）：输入联想为匿名只读接口，按 IP 防爬（1.3） */
    public static final int SUGGEST_IP_MAX_COUNT = 20;
}
