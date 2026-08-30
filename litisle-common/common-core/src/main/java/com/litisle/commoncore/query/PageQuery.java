package com.litisle.commoncore.query;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.commoncore.constant.PageConstant;
import io.micrometer.common.util.StringUtils;
import lombok.Data;


/**
 * 分页查询参数基类
 * <p>
 * 供各业务查询 DTO 继承（如 {@code UserQuery extends PageQuery}），统一承载
 * 页码、每页条数、排序字段与方向，并提供 MyBatis-Plus {@link Page} 的构建能力。
 *
 * 【设计说明】
 * 1. 字段带默认值，配合后端兜底钳制，防御前端传入 null / 越界值覆盖默认值
 * 2. 排序走 {@link OrderItem}，不拼接 SQL 字符串，规避基础 SQL 注入
 * 3. 排序三级优先级：代码显式 OrderItem &gt; 前端 sortBy &gt; 默认按更新时间降序
 * 4. 若业务需将 sortBy 用于二次 SQL 拼装，请在子 Query 中自行做列名白名单校验
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
        // 1. 分页参数兜底 + 上限钳制（防御 null / 越界覆盖默认值）
        Page<T> page = Page.of(normalizePageNo(), normalizePageSize());

        // 2. 排序：三级优先级
        if (items != null && items.length > 0) {
            // 优先级1：后端代码显式传入的排序条件
            for (OrderItem item : items) {
                if (item != null) {
                    page.addOrder(item);
                }
            }
        } else if (StringUtils.isNotBlank(sortBy)) {
            // 优先级2：前端传入的排序字段（结合 isAsc 决定方向）
            String column = sortBy.trim();
            page.addOrder(Boolean.TRUE.equals(isAsc) ? OrderItem.asc(column) : OrderItem.desc(column));
        } else {
            // 优先级3：默认按更新时间降序
            page.addOrder(OrderItem.desc(PageConstant.UPDATE_TIME));
        }

        return page;
    }

    //保证返回的页码一定 ≥ 1 且不为 null
    /**
     * 归一化页码：null 或小于 1 时回退默认值
     * "页码不能小于 1，否则当第 1 页
     */
    private int normalizePageNo() {
        return (pageNo == null || pageNo < 1) ? PageConstant.DEFAULT_PAGE_NO : pageNo;
    }

    /**
     * 归一化每页条数：null 或小于 1 时回退默认值，并钳制到上限以内
     * "每页条数不能小于 1，否则当默认值；不能大于上限，否则截断到上限"
     */
    private int normalizePageSize() {
        if (pageSize == null || pageSize < 1) {
            return PageConstant.DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, PageConstant.MAX_PAGE_SIZE);
    }
}