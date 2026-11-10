package com.litisle.litislesearch.constant;

/**
 * search 模块自有表名常量（批次六 6.2 搜索历史收口）。
 *
 * 说明：表名是 SQL 与 MyBatis 映射的对齐依据，不适合外置 yml，统一收口避免字符串硬编码
 */
public final class SearchHistoryConstant {

    /** 工具类禁止实例化 */
    private SearchHistoryConstant() {
    }

    /** search_history 表名（search 唯一自有表） */
    public static final String TABLE_SEARCH_HISTORY = "search_history";
}
