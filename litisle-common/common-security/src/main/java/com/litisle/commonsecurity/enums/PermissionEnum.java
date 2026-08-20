package com.litisle.commonsecurity.enums;

import com.litisle.commonsecurity.constant.PermissionCode;
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
    USER_READ(PermissionCode.USER_READ, "查看用户"),
    USER_UPDATE(PermissionCode.USER_UPDATE, "修改用户"),
    USER_DELETE(PermissionCode.USER_DELETE, "删除用户"),
    USER_BAN(PermissionCode.USER_BAN, "封禁用户"),

    /* ================= 敏感信息（⚠️ 高风险） ================= */
    SENSITIVE_PHONE_READ(PermissionCode.SENSITIVE_PHONE_READ, "查看手机号"),
    SENSITIVE_EMAIL_READ(PermissionCode.SENSITIVE_EMAIL_READ, "查看邮箱"),
    SENSITIVE_PASSWORD_READ(PermissionCode.SENSITIVE_PASSWORD_READ, "查看密码"),
    SENSITIVE_EXPORT(PermissionCode.SENSITIVE_EXPORT, "导出敏感数据"),

    /* ================= 角色 / 权限 ================= */
    ROLE_ASSIGN(PermissionCode.ROLE_ASSIGN, "分配角色"),
    ROLE_PERM_UPDATE(PermissionCode.ROLE_PERM_UPDATE, "修改角色权限"),

    /* ================= 系统 ================= */
    SYS_CONFIG_UPDATE(PermissionCode.SYS_CONFIG_UPDATE, "修改系统配置"),
    SYS_DICT_UPDATE(PermissionCode.SYS_DICT_UPDATE, "修改数据字典"),

    /* ================= 内容（示例） ================= */
    ARTICLE_READ(PermissionCode.ARTICLE_READ, "查看文章"),
    ARTICLE_PUBLISH(PermissionCode.ARTICLE_PUBLISH, "发布文章"),
    ARTICLE_DELETE(PermissionCode.ARTICLE_DELETE, "删除文章");



    /** 权限码 */
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