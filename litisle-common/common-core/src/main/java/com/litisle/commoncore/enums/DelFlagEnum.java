package com.litisle.commoncore.enums;


import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 删除标志枚举
 * 
 * 【设计说明】
 * 1. 用于逻辑删除（软删除），数据库中 del_flag 字段
 * 2. 逻辑删除：不真正删除数据，而是标记为已删除
 * 3. 好处：数据可恢复，便于审计追溯
 * 
 * 【数据库设计】
 * - 字段：del_flag tinyint NOT NULL DEFAULT 0
 * - 0：未删除（正常数据）
 * - 1：已删除（逻辑删除）
 * 
 * 【MyBatis-Plus 配置】
 * 在 application.yml 中配置逻辑删除：
 * mybatis-plus:
 *   global-config:
 *     db-config:
 *       logic-delete-field: delFlag
 *       logic-delete-value: 1
 *       logic-not-delete-value: 0
 * 
 * @author litisle
 */
@Getter
@AllArgsConstructor
public enum DelFlagEnum {

    /**
     * 未删除
     */
    NOT_DELETED(0, "未删除"),

    /**
     * 已删除
     */
    DELETED(1, "已删除");

    /**
     * 删除标志值
     */
    private final int code;

    /**
     * 描述
     */
    private final String desc;
}