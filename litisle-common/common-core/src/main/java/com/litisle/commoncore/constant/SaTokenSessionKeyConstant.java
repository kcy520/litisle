package com.litisle.commoncore.constant;

/**
 * Sa-Token Session 键常量类
 *
 * 【设计说明】
 * 1. 统一管理 Sa-Token Session 中存储的自定义键名
 * 2. 避免写入方与读取方各自硬编码字符串，拼写不一致时静默失效（get 返回 null 不报错，极难排查）
 *
 * 【写入方】
 * - litisle-auth：登录/注册成功后（AuthServiceImpl#buildTsuccessenResponse）
 *
 * 【读取方】
 * - common-security 的 StpInterfaceImpl（角色/权限鉴权数据源）
 * - common-security 的 FeignAuthInterceptor（用户名透传）
 *
 * @author litisle
 */
public class SaTokenSessionKeyConstant {

    /**
     * 角色编码列表（List<String>，如 ["READER", "AUTHOR"]）
     */
    public static final String ROLES = "roles";

    /**
     * 权限编码列表（List<String>，如 ["article:read", "article:write"]）
     */
    public static final String PERMISSIONS = "permissions";

    /**
     * 用户名
     */
    public static final String USERNAME = "username";

    private SaTokenSessionKeyConstant() {
    }
}
