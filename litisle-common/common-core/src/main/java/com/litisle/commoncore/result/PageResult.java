package com.litisle.commoncore.result;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 分页响应结果封装类
 * 
 * 【设计说明】
 * 1. 用于分页查询接口的响应，包含分页信息和数据列表
 * 2. 前端根据 total 和 pageSize 计算总页数，展示分页组件
 * 
 * 【字段说明】
 * - pageNum：当前页码（从 1 开始）
 * - pageSize：每页大小
 * - total：总记录数
 * - list：当前页数据列表
 * 
 * 【使用示例】
 * // Controller
 * public Result<PageResult<UserVO>> listUsers(@RequestParam(defaultValue = "1") int pageNum,
 *                                             @RequestParam(defaultValue = "10") int pageSize) {
 *     Page<User> page = userService.page(new Page<>(pageNum, pageSize));
 *     List<UserVO> voList = convertToVO(page.getRecords());
 *     return Result.success(PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), voList));
 * }
 * 
 * @author litisle
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> implements Serializable {

    /**
     * 当前页码（从 1 开始）
     */
    private long pageNum;

    /**
     * 每页大小
     */
    private long pageSize;

    /**
     * 总记录数
     */
    private long total;

    /**
     * 当前页数据列表
     */
    private List<T> list;

    /**
     * 静态工厂方法：创建分页响应对象
     * 
     * @param pageNum  当前页码
     * @param pageSize 每页大小
     * @param total    总记录数
     * @param list     数据列表
     * @param <T>      数据类型
     * @return PageResult 对象
     */
    public static <T> PageResult<T> of(long pageNum, long pageSize, long total, List<T> list) {
        return new PageResult<>(pageNum, pageSize, total, list);
    }
}