package com.litisle.commoncore.query;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.commoncore.constant.PageConstant;
import io.micrometer.common.util.StringUtils;
import lombok.Data;

import java.util.Set;


/**
 * 分页查询参数基类
 * <p>
 * 供各业务查询 DTO 继承（如 {@code UserQuery extends PageQuery}），统一承载
 * 页码、每页条数、排序字段与方向，并提供 MyBatis-Plus {@link Page} 的构建能力。
 *
 * 【设计说明】
 * 1. 字段带默认值，配合后端兜底钳制，防御前端传入 null / 越界值覆盖默认值
 * 2. 排序最终经 {@link OrderItem} 由 MyBatis-Plus 分页插件拼入 ORDER BY；
 *    sortBy 先做驼峰转下划线，再过白名单校验（非法值静默回退默认排序，宁严勿松）；
 *    分页插件自带的 SQL 黑名单校验仅作兜底，不能作为唯一防线依赖
 * 3. 排序三级优先级：代码显式 OrderItem &gt; 前端 sortBy（白名单内）&gt; 默认按更新时间降序
 * 4. 需要扩展可排序列的业务，覆写 {@link #sortableColumns()} 返回各自的列名白名单
 *
 * @author litisle
 */
@Data
public class PageQuery {

    /** 当前页码（从 1 开始） */
    private Integer pageNo = PageConstant.DEFAULT_PAGE_NO;

    /** 每页条数 */
    private Integer pageSize = PageConstant.DEFAULT_PAGE_SIZE;

    /** 排序字段（可选，走 OrderItem，安全） */
    private String sortBy;

    /** 是否升序（null 或 false 均视为降序） */
    private Boolean isAsc;

    /**
     * 构建 MyBatis-Plus 分页对象（核心方法）
     *
     * @param items 业务方显式指定的排序条件，优先级最高；可省略
     * @param <T>   查询实体类型
     * @return 已配置分页与排序的 Page 对象
     */
    public <T> Page<T> toMpPage(OrderItem... items) {
        return toMpPage(true, items);
    }

    /**
     * 构建 MyBatis-Plus 分页对象（可关闭 count 查询）
     *
     * @param searchCount false=不执行 count 总数查询（瀑布流/无限滚动场景，total 无意义时省一次全表计数开销）
     * @param items       业务方显式指定的排序条件，优先级最高；可省略
     * @param <T>         查询实体类型
     * @return 已配置分页与排序的 Page 对象
     */
    public <T> Page<T> toMpPage(boolean searchCount, OrderItem... items) {
        // 1. 分页参数兜底 + 上限钳制（防御 null / 越界覆盖默认值）
        Page<T> page = Page.of(normalizePageNo(), normalizePageSize(), searchCount);

        // 2. 排序：三级优先级
        if (items != null && items.length > 0) {
            // 优先级1：后端代码显式传入的排序条件
            for (OrderItem item : items) {
                if (item != null) {
                    page.addOrder(item);
                }
            }
        } else if (StringUtils.isNotBlank(sortBy)) {
            // 优先级2：前端传入的排序字段（驼峰转下划线 + 白名单校验；非法值静默回退默认排序）
            String column = resolveSafeSortColumn(sortBy);
            if (column != null) {
                page.addOrder(Boolean.TRUE.equals(isAsc) ? OrderItem.asc(column) : OrderItem.desc(column));
            } else {
                page.addOrder(OrderItem.desc(PageConstant.UPDATE_TIME));
            }
        } else {
            // 优先级3：默认按更新时间降序
            page.addOrder(OrderItem.desc(PageConstant.UPDATE_TIME));
        }

        return page;
    }

    /**
     * 可排序白名单（默认仅通用时间列；业务子类覆写扩展，如文章侧的 view_cnt/like_cnt）
     *
     * @return 允许作为 ORDER BY 的数据库列名集合（下划线格式）
     */
    protected Set<String> sortableColumns() {
        return PageConstant.DEFAULT_SORTABLE_COLUMNS;
    }

    /**
     * 排序字段安全解析：驼峰转下划线 + 白名单校验
     *
     * @param raw 前端原始排序字段（如 updateTime）
     * @return 白名单内的数据库列名（如 update_time）；非法值返回 null（调用方回退默认排序）
     */
    private String resolveSafeSortColumn(String raw) {
        String column = raw.trim()
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase();
        return sortableColumns().contains(column) ? column : null;
    }

    /**
     * 归一化页码：null 或小于 1 时回退默认值
     * <p>"页码不能小于 1，否则当作第 1 页"</p>
     */
    private int normalizePageNo() {
        return (pageNo == null || pageNo < 1) ? PageConstant.DEFAULT_PAGE_NO : pageNo;
    }

    /**
     * 归一化每页条数：null 或小于 1 时回退默认值，并钳制到上限以内
     * <p>"每页条数不能小于 1，否则用默认值；不能大于上限，否则截断到上限"</p>
     */
    private int normalizePageSize() {
        if (pageSize == null || pageSize < 1) {
            return PageConstant.DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, PageConstant.MAX_PAGE_SIZE);
    }
}