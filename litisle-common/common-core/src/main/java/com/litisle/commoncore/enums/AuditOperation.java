package com.litisle.commoncore.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 审计操作（二级分类）
 * 每个模块下明确“能干什么”
 */
@Getter
@AllArgsConstructor
public enum AuditOperation {

    // ===== 用户 =====
    USER_CREATE(AuditModule.USER, "create", "创建用户"),
    USER_UPDATE(AuditModule.USER, "update", "修改用户"),
    USER_DELETE(AuditModule.USER, "delete", "删除用户"),
    USER_BAN(AuditModule.USER, "ban", "封禁用户"),
    USER_UNBAN(AuditModule.USER, "unban", "解封用户"),
    USER_RESET_PWD(AuditModule.USER, "reset_pwd", "重置密码"),

    // ===== OAuth =====
    OAUTH_BIND(AuditModule.OAUTH, "bind", "绑定第三方"),
    OAUTH_UNBIND(AuditModule.OAUTH, "unbind", "解绑第三方"),

    // ===== 钱包 =====
    WALLET_WITHDRAW(AuditModule.WALLET, "withdraw", "提现申请"),
    WALLET_RECHARGE(AuditModule.WALLET, "recharge", "充值"),

    // ===== 系统 =====
    SYS_CONFIG_UPDATE(AuditModule.SYSTEM, "config_update", "修改系统配置"),

    // ===== 用户敏感信息（✅ 合规/风控必留）=====
    USER_EXPORT(AuditModule.SENSITIVE, "export", "导出用户数据"),

    USER_VIEW_PHONE_SELF(AuditModule.SENSITIVE, "view_phone_self", "本人查看手机号"),
    USER_VIEW_PHONE_ADMIN(AuditModule.SENSITIVE, "view_phone_admin", "管理员查看用户手机号"),
    USER_VIEW_EMAIL_SELF(AuditModule.SENSITIVE, "view_email_self", "本人查看邮箱"),
    USER_VIEW_EMAIL_ADMIN(AuditModule.SENSITIVE, "view_email_admin", "管理员查看用户邮箱"),
    USER_VIEW_PASSWORD_SELF(AuditModule.SENSITIVE, "view_password_self", "本人查看密码"),
    USER_VIEW_PASSWORD_ADMIN(AuditModule.SENSITIVE, "view_password_admin", "管理员查看用户密码"),

    // ===== 登录/认证（等保常查）=====
    AUTH_LOGIN(AuditModule.AUTH, "login", "用户登录"),
    AUTH_LOGIN_FAIL(AuditModule.AUTH, "login_fail", "登录失败"),
    AUTH_LOGOUT(AuditModule.AUTH, "logout", "退出登录"),

    // ===== 角色权限（权限变更 = 高风险）=====
    ROLE_ASSIGN(AuditModule.ROLE, "assign", "分配角色"),
    ROLE_REVOKE(AuditModule.ROLE, "revoke", "回收角色"),
    PERM_UPDATE(AuditModule.ROLE, "perm_update", "修改权限"),

    // ===== 订单（业务核心）=====
    ORDER_CREATE(AuditModule.ORDER, "create", "创建订单"),
    ORDER_CANCEL(AuditModule.ORDER, "cancel", "取消订单"),
    ORDER_REFUND(AuditModule.ORDER, "refund", "退款"),

    // ===== 钱包补充 =====
    WALLET_TRANSFER(AuditModule.WALLET, "transfer", "转账"),

    // ===== 系统补充 =====
    SYS_DICT_UPDATE(AuditModule.SYSTEM, "dict_update", "修改数据字典"),
    SYS_LOGIN_WHITELIST(AuditModule.SYSTEM, "whitelist_update", "登录白名单变更");


    private final AuditModule module;
    private final String code;
    private final String desc;

    public String full() {
        return module.getCode() + "." + code;
    }
}