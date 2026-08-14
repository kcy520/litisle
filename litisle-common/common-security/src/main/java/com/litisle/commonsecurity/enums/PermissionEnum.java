package com.litisle.commonsecurity.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Set;

/**
 * 系统权限点（permission）
 * <p>
 * 规范：
 * 1. 格式：resource:action
 * 2. 只代表“能不能做”，不代表“是谁”
 * 3. 角色 → 权限 由 RBAC 表维护
 * 4. 新增权限必须 MR Review
 *
 * @author litisle
 */
@Getter
@AllArgsConstructor
public enum PermissionEnum {

    /* ================= 用户 ================= */
    USER_READ("user:read", "查看用户"),
    USER_UPDATE("user:update", "修改用户"),
    USER_DELETE("user:delete", "删除用户"),
    USER_BAN("user:ban", "封禁用户"),

    /* ================= 敏感信息（⚠️ 高风险） ================= */
    SENSITIVE_PHONE_READ("sensitive:phone:read", "查看手机号"),
    SENSITIVE_EMAIL_READ("sensitive:email:read", "查看邮箱"),
    SENSITIVE_PASSWORD_READ("sensitive:password:read", "查看密码"),
    SENSITIVE_EXPORT("sensitive:export", "导出敏感数据"),

    /* ================= 角色 / 权限 ================= */
    ROLE_ASSIGN("role:assign", "分配角色"),
    ROLE_PERM_UPDATE("role:perm:update", "修改角色权限"),

    /* ================= 系统 ================= */
    SYS_CONFIG_UPDATE("system:config:update", "修改系统配置"),
    SYS_DICT_UPDATE("system:dict:update", "修改数据字典"),

    /* ================= 内容（示例） ================= */
    ARTICLE_READ("article:read", "查看文章"),
    ARTICLE_PUBLISH("article:publish", "发布文章"),
    ARTICLE_DELETE("article:delete", "删除文章");

    /** 权限码（与 RequirePermission 注解对应） */
    private final String code;

    /** 展示用 */
    private final String desc;

    /** 返回字符串数组，方便注解用 */
    public String[] toArray() {
        return new String[]{code};
    }

    /** 常用：敏感权限集合（风控用） */
    public static Set<PermissionEnum> sensitivePermissions() {
        return Set.of(
            SENSITIVE_PHONE_READ,
            SENSITIVE_EMAIL_READ,
            SENSITIVE_PASSWORD_READ,
            SENSITIVE_EXPORT
        );
    }
}