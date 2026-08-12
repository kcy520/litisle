package com.litisle.commonsecurity.context;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Set;


/**
 * SecurityContext 与 SecurityContextHolder 的职责分离：
 *
 * 1. SecurityContext
 *    - 用户认证/授权数据的载体（POJO）
 *    - 与线程、请求、容器无关
 *    - 可序列化、可传递、可单测
 *    - 承载 hasRole / hasPermission 等“用户本身的能力”
 *
 * 2. SecurityContextHolder
 *    - 线程级访问入口（ThreadLocal 工具）
 *    - 负责生命周期：set / clear
 *    - 为业务/切面提供“无参获取当前用户”的快捷 API
 *    - 所有静态方法都是对 SecurityContext 的空安全委托
 *
 * 二者不是重复设计，而是“数据”与“作用域”的强制解耦。
 */


/**
 * 安全上下文（封装当前请求的用户信息）
 *
 *Context管“内容（用户）”
 *
 * 【设计说明】
 * 1. 封装当前登录用户的核心信息（userId、username、roles）
 * 2. 通过 SecurityContextHolder 获取，避免业务代码从 Header 解析
 * 3. 不直接使用 Spring Security 的 Authentication，简化使用
 * 
 * 【使用场景】
 * 业务代码中获取当前用户：
 * Long userId = SecurityContextHolder.getContext().getUserId();
 * Set<String> roles = SecurityContextHolder.getContext().getRoles();
 * 
 * 【与 Spring Security 的关系】
 * - Spring Security 的 SecurityContextHolder 存储的是 Authentication 对象
 * - 本类是对其的简化封装，业务代码无需感知 Spring Security 的存在
 * - JwtAuthenticationFilter 负责从 Header 解析并写入本对象
 * 
 * @author litisle
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecurityContext implements Serializable {

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 用户名
     */
    private String username;

    /**
     * 用户角色集合（如 ADMIN、READER、AUTHOR）
     * 【说明】使用 Set 去重，支持一个用户拥有多个角色
     */
    private Set<String> roles;

    /**
     * 用户权限集合（如 user:read、article:publish）
     * 【说明】细粒度权限码，由角色映射而来（角色-权限映射存于 DB，由 auth 服务管理）
     *       权限码格式：资源:动作（如 user:delete）
     *       由 JwtAuthenticationFilter 解析 JWT 后写入
     *       配合 @RequirePermission 注解和 PermissionCheckAspect 切面使用
     */
    private Set<String> permissions;

    /**
     * Token 唯一标识（用于踢人/黑名单场景）
     * 【说明】网关签发 JWT 时生成 jti（JWT ID），下游服务可通过此 ID 实现：
     *       - 强制下线：将该 tokenId 加入黑名单
     *       - 会话管理：记录用户活跃 token 列表
     */
    private String tokenId;

    /**
     * 链路追踪ID（用于日志关联）
     */
    private String traceId;

    /**
     * 判断是否已登录
     * 
     * @return true=已登录，false=未登录
     */
    public boolean isAuthenticated() {
        return userId != null;
    }

    /**
     * 判断是否拥有某个角色
     * 
     * 【使用示例】
     * if (context.hasRole("ADMIN")) { ... }
     * 
     * @param role 角色编码
     * @return true=拥有该角色
     */
    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }

    /**
     * 判断是否拥有任意一个角色（满足其一即可）
     * 
     * 【使用场景】
     * - ADMIN 或 SUPER_ADMIN 才能访问
     * context.hasAnyRole("ADMIN", "SUPER_ADMIN")
     * 
     * @param roleList 角色编码列表
     * @return true=拥有任意一个角色
     */
    public boolean hasAnyRole(String... roleList) {
        if (roles == null || roleList == null) {
            return false;
        }
        for (String role : roleList) {
            if (roles.contains(role)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否拥有某个权限
     *
     * 【使用示例】
     * if (context.hasPermission("user:delete")) { ... }
     *
     * @param permission 权限编码（如 "user:delete"）
     * @return true=拥有该权限
     */
    public boolean hasPermission(String permission) {
        return permissions != null && permissions.contains(permission);
    }

}