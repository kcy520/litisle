package com.litisle.commoncore.constant;



/**
 * HTTP Header 常量类
 * 
 * 【设计说明】
 * 1. 存放网关、服务间传递时使用的 Header 名称
 * 2. 网关校验 JWT 后，将用户信息注入 Header，下游服务从 Header 读取
 * 3. 统一常量管理，避免拼写错误
 * 
 * 【使用场景】
 * - 网关：JwtGlobalFilter 校验 JWT 后，将 userId、roles 写入 Header
 * - 业务服务：SecurityContextHolder 从 Header 读取用户信息
 * - Feign 调用：FeignAuthInterceptor 自动透传 Header
 * 
 * @author litisle
 */
public class HeaderConstant {

    /**
     * 用户ID（网关注入，业务服务读取）
     */
    public static final String USER_ID = "X-User-Id";

    /**
     * 用户角色（多个角色用逗号分隔，如 "ADMIN,READER"）
     */
    public static final String USER_ROLES = "X-Roles";

    /**
     * 用户名
     */
    public static final String USERNAME = "X-Username";

    /**
     * 用户权限（细粒度）
     * 示例：article:publish,comment:delete,user:read
     */
    public static final String PERMISSIONS = "X-User-Permissions";

    /**
     * Token 唯一标识（用于踢人/黑名单）
     */
    public static final String TOKEN_ID = "X-Token-Id";

    /**
     * 链路追踪ID（用于日志追踪，跨服务排查问题）
     */
    public static final String TRACE_ID = "X-Trace-Id";
}