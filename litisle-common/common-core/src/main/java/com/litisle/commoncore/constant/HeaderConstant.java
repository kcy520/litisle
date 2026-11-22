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
 * - 业务服务：网关过滤器从 Header 读取用户信息，注入 Sa-Token 上下文
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

    /**
     * 设备ID（标识当前请求来源设备，用于多设备登录管理）
     * 登录时由 auth 模块生成并下发，客户端后续请求应在 Header 中携带
     * 示例：X-Device-Id: a1b2c3d4-e5f6-7890-abcd-ef1234567890
     */
    public static final String DEVICE_ID = "X-Device-Id";

    /**
     * 客户端真实 IP 请求头（支持多级代理，多级时取首个）
     * 优先级：X-Forwarded-For > X-Real-IP > RemoteAddr
     */
    public static final String X_FORWARDED_FOR = "X-Forwarded-For";

    /**
     * 反向代理真实 IP 请求头
     */
    public static final String X_REAL_IP = "X-Real-IP";

    public static final String USER_AGENT = "User-Agent";

    /**
     * 客户端语言偏好头（与 User-Agent 组合参与设备指纹计算）
     */
    public static final String ACCEPT_LANGUAGE = "Accept-Language";
}