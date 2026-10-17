package com.litisle.commoncore.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 审计模块（一级分类）
 * 变更需要走发版
 */
@Getter
@AllArgsConstructor
public enum AuditModule {

    USER("user", "用户管理"),
    ROLE("role", "角色权限"),
    SENSITIVE("sensitive", "敏感信息"),
    AUTH("auth", "认证"),
    OAUTH("oauth", "第三方登录"),
    ORDER("order", "订单"),
    WALLET("wallet", "钱包/提现"),
    SYSTEM("system", "系统配置"),
    /**
     * 内容管理（文章/书籍治理动作：删除/下架等，板块一 [1.2/1.3] 扩展）
     */
    CONTENT("content", "内容管理");

    private final String code;
    private final String desc;
}