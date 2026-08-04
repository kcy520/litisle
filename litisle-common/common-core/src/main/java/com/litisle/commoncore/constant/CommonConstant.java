package com.litisle.commoncore.constant;


/**
 * 通用常量类
 * 
 * 【设计说明】
 * 1. 存放跨模块共享的通用常量
 * 2. 避免硬编码，提高可维护性
 * 3. 所有常量使用 static final 修饰，确保不可变
 * 
 * @author litisle
 */
public class CommonConstant {

    /**
     * UTF-8 字符集
     */
    public static final String UTF8 = "UTF-8";

    /**
     * 默认分页大小
     */
    public static final int DEFAULT_PAGE_SIZE = 10;

    /**
     * 最大分页大小（防止一次性查询过多数据）
     */
    public static final int MAX_PAGE_SIZE = 100;

    /**
     * 成功标识
     */
    public static final int SUCCESS = 0;

    /**
     * 失败标识
     */
    public static final int FAIL = 1;
}