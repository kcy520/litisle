package com.litisle.commoncore.enums;


import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 通用状态枚举
 * 
 * 【设计说明】
 * 1. 用于表示数据的启用/禁用状态
 * 2. 数据库字段 status 通常使用 tinyint，对应枚举的 code
 * 
 * 【使用场景】
 * - 用户状态：0-禁用 1-正常
 * - 文章状态：0-草稿 1-待审核 2-已发布 3-驳回 4-下架
 * - 商品状态：0-下架 1-上架
 * 
 * @author litisle
 */
@Getter
@AllArgsConstructor
public enum StatusEnum {

    /**
     * 禁用
     */
    DISABLE(0, "禁用"),

    /**
     * 正常
     */
    ENABLE(1, "正常");

    /**
     * 状态码
     */
    private final int code;

    /**
     * 状态描述
     */
    private final String desc;
}