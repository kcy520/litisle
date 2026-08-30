package com.litisle.commonsecurity.constant;

/**
 * 系统角色码常量
 * <p>
 * 规范：
 * 1. 格式：与 RoleCodeEnum.code 一一对应
 * 2. 只代表"是什么身份"，不代表"能做什么"
 * 3. 与 RoleCodeEnum 一一对应，新增角色码必须同步维护枚举
 * 4. 注解中使用此类常量，禁止硬编码字符串
 * 5. 新增角色码必须 MR Review
 *
 * @author litisle
 */
public final class RoleCode {

    private RoleCode() {}

    /* ================= 普通层 ================= */
    public static final String READER = "READER";

    /* ================= 业务层 ================= */
    public static final String AUTHOR = "AUTHOR";
    public static final String LEGACY_AUTHOR = "LEGACY_AUTHOR";

    /* ================= 审核层 ================= */
    public static final String REVIEWER = "REVIEWER";

    /* ================= 管理层 ================= */
    public static final String ADMIN = "ADMIN";

    /* ================= 超管层 ================= */
    public static final String SUPER_ADMIN = "SUPER_ADMIN";
}