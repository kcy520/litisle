package com.litisle.commonsecurity.constant;

/**
 * 系统权限码常量
 * <p>
 * 规范：
 * 1. 格式：resource:action
 * 2. 只代表"能不能做"，不代表"是谁"
 * 3. 与 PermissionEnum 一一对应
 * 4. 新增权限码必须 MR Review
 *
 * @author litisle
 */
public final class PermissionCode {

    private PermissionCode() {}

    /* ================= 用户 ================= */
    public static final String USER_READ = "user:read";
    public static final String USER_UPDATE = "user:update";
    public static final String USER_DELETE = "user:delete";
    public static final String USER_BAN = "user:ban";

    /* ================= 敏感信息（⚠️ 高风险） ================= */
    public static final String SENSITIVE_PHONE_READ = "sensitive:phone:read";
    public static final String SENSITIVE_EMAIL_READ = "sensitive:email:read";
    public static final String SENSITIVE_PASSWORD_READ = "sensitive:password:read";
    public static final String SENSITIVE_EXPORT = "sensitive:export";

    /* ================= 角色 / 权限 ================= */
    public static final String ROLE_ASSIGN = "role:assign";
    public static final String ROLE_PERM_UPDATE = "role:perm:update";

    /* ================= 系统 ================= */
    public static final String SYS_CONFIG_UPDATE = "system:config:update";
    public static final String SYS_DICT_UPDATE = "system:dict:update";

    /* ================= 内容 ================= */
    public static final String ARTICLE_READ = "article:read";
    public static final String ARTICLE_PUBLISH = "article:publish";
    public static final String ARTICLE_DELETE = "article:delete";
}