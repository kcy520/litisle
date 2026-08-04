package com.litisle.commoncore.utils;


import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.commoncore.result.PageResult;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 分页工具类
 * 
 * 【设计说明】
 * 1. 封装 MyBatis-Plus 的分页对象 Page，简化使用
 * 2. 提供 Entity → VO 的转换方法
 * 3. 统一返回 PageResult，前端按约定解析
 * 
 * 【使用示例】
 * // 方式1：直接返回 PageResult
 * Page<User> page = new Page<>(pageNum, pageSize);
 * IPage<User> result = userMapper.selectPage(page, null);
 * PageResult<UserVO> pageResult = PageUtil.convert(result, user -> convertToVO(user));
 * 
 * // 方式2：先转换 List，再包装
 * List<UserVO> voList = result.getRecords().stream().map(this::convertToVO).collect(Collectors.toList());
 * PageResult<UserVO> pageResult = PageUtil.of(result.getCurrent(), result.getSize(), result.getTotal(), voList);
 * 
 * @author litisle
 */
public class PageUtil {

    /**
     * 将 MyBatis-Plus 的 IPage 转换为 PageResult
     * 
     * @param page     MyBatis-Plus 分页对象
     * @param converter Entity → VO 转换函数
     * @param <E>      Entity 类型
     * @param <V>      VO 类型
     * @return PageResult 对象
     */
    public static <E, V> PageResult<V> convert(IPage<E> page, Function<E, V> converter) {
        List<V> list = page.getRecords().stream()
                .map(converter)
                .collect(Collectors.toList());
        
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), list);
    }

    /**
     * 创建 PageResult 对象
     * 
     * @param pageNum  当前页码
     * @param pageSize 每页大小
     * @param total    总记录数
     * @param list     数据列表
     * @param <T>      数据类型
     * @return PageResult 对象
     */
    public static <T> PageResult<T> of(long pageNum, long pageSize, long total, List<T> list) {
        return PageResult.of(pageNum, pageSize, total, list);
    }

    /**
     * 创建 MyBatis-Plus 的 Page 对象
     * 
     * 【使用示例】
     * Page<User> page = PageUtil.of(1, 10);
     * 
     * @param pageNum  当前页码（从 1 开始）
     * @param pageSize 每页大小
     * @param <T>      数据类型
     * @return Page 对象
     */
    public static <T> Page<T> of(long pageNum, long pageSize) {
        return new Page<>(pageNum, pageSize);
    }
}