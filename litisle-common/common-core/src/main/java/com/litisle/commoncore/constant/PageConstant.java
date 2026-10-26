package com.litisle.commoncore.constant;

import java.util.Set;

/**
 * 分页公共常量
 * <p>
 * 统一管理分页查询的默认值、上限与常用排序列，供各业务模块的分页参数复用，
 * 避免硬编码与各模块各自定义带来的不一致。
 *
 * @author litisle
 */
public final class PageConstant {

    private PageConstant() {
    }

    /** 默认页码（从 1 开始） */
    public static final int DEFAULT_PAGE_NO = 1;

    /** 默认每页条数 */
    public static final int DEFAULT_PAGE_SIZE = 10;

    /** 每页条数上限（防止超大 pageSize 拖垮数据库） */
    public static final int MAX_PAGE_SIZE = 100;

    /** 通用排序列：更新时间 */
    public static final String UPDATE_TIME = "update_time";

    /** 通用排序列：创建时间 */
    public static final String CREATE_TIME = "create_time";

    /** 默认可排序白名单（仅通用时间列；业务子类覆写 PageQuery#sortableColumns() 扩展，如 view_cnt/like_cnt） */
    public static final Set<String> DEFAULT_SORTABLE_COLUMNS = Set.of(UPDATE_TIME, CREATE_TIME);
}