package com.litisle.commonsecurity.jwt;

/**
 * JWT 常量类
 *
 * 【设计说明】
 * 1. 集中管理 JWT 相关的 Claims 名称、Header 名称等常量
 * 2. 网关和业务服务统一引用，避免拼写错误
 * 3. 与 HeaderConstant 配合使用：JWT Claims 中的字段名与此处定义一致
 *
 * 【Claims 设计】
 * - sub: 用户ID（String 类型，兼容性考虑）
 * - username: 用户名
 * - roles: 角色集合（逗号分隔）
 * - permissions: 权限集合（逗号分隔）
 * - jti: JWT 唯一标识（用于黑名单）
 *
 * @author litisle
 */
public class JwtConstant {

    private JwtConstant() {
        // 工具类禁止实例化
    }

    // ==================== JWT Claims 名称 ====================

    /**
     * 用户ID Claim
     * 对应 HeaderConstant.USER_ID（X-User-Id）
     * 存储为 String 类型，兼容不同语言客户端
     */
    public static final String CLAIM_USER_ID = "userId";

    /**
     * 用户名 Claim
     * 对应 HeaderConstant.USERNAME（X-Username）
     */
    public static final String CLAIM_USERNAME = "username";

    /**
     * 角色集合 Claim
     * 对应 HeaderConstant.USER_ROLES（X-Roles）
     * 多个角色用逗号分隔，如 "ADMIN,READER"
     */
    public static final String CLAIM_ROLES = "roles";

    /**
     * 权限集合 Claim
     * 对应 HeaderConstant.PERMISSIONS（X-User-Permissions）
     * 多个权限用逗号分隔，如 "article:publish,comment:delete"
     */
    public static final String CLAIM_PERMISSIONS = "permissions";

    // ==================== JWT Header 名称 ====================

    /**
     * 令牌类型 Header
     * 标准 JWT Header，值为 "JWT"
     */
    public static final String HEADER_TYP = "typ";

    /**
     * 签名算法 Header
     * 标准 JWT Header，值为 "HS256"
     */
    public static final String HEADER_ALG = "alg";

    // ==================== 请求头常量 ====================

    /**
     * Authorization 请求头
     */
    public static final String AUTHORIZATION_HEADER = "Authorization";

    /**
     * Bearer 令牌前缀
     * 格式：Bearer {token}
     */
    public static final String BEARER_PREFIX = "Bearer ";

    /**
     * Bearer 前缀长度（用于截取 Token）
     */
    public static final int BEARER_PREFIX_LENGTH = 7;

