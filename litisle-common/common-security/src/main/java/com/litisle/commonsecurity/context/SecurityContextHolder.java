package com.litisle.commonsecurity.context;

import java.util.Set;

/**
 * 安全上下文持有者（基于 ThreadLocal）
 * 
 * 【设计说明】
 * 1. 使用 ThreadLocal 存储当前线程的 SecurityContext
 * 2. 每个请求在独立线程中处理，互不干扰
 * 3. 请求结束后必须清理 ThreadLocal，避免内存泄漏
 * 
 * 【ThreadLocal 原理】
 * - 每个 Thread 内部有一个 ThreadLocalMap
 * - ThreadLocal 作为 Key，存储的值作为 Value
 * - 不同线程的 ThreadLocalMap 互相隔离
 * 
 * 【内存泄漏问题】
 * - 线程池中的线程会复用，如果不清理 ThreadLocal，会导致数据串号
 * - 解决方案：在 Filter 的 finally 块中调用 clear()
 * 
 * 【使用示例】
 * // 设置当前用户（通常在 Filter 中）
 * SecurityContextHolder.setContext(securityContext);
 * 
 * // 获取当前用户（在 Service、Controller 中）
 * Long userId = SecurityContextHolder.getUserId();
 * SecurityContext context = SecurityContextHolder.getContext();
 * 
 * // 清理（在 Filter 的 finally 中）
 * SecurityContextHolder.clear();
 * 
 * @author litisle
 */
public class SecurityContextHolder {

    /**
     * ThreadLocal 存储 SecurityContext
     * 
     * 【说明】
     * - 使用 withInitial 方式创建，初始值为 null
     * - 使用 InheritableThreadLocal：子线程可以继承父线程的上下文
     *   适用于异步任务场景（如 @Async）
     *   注意：线程池场景需配合 TransmittableThreadLocal
     *        TransmittableThreadLocal :不在 new Thread() 时拷贝，而是在 submit()/execute() 的那一刻，把当前父线程的值"注入"到线程池线程中。
     *        防止清理父线程上下文时，没有清理子线程的，而子线程是可以复用的，如果是改为父线程提交时将值注入子线程，（而不是new时拷贝）就可以覆盖原来的脏数据
     */
    private static final ThreadLocal<SecurityContext> CONTEXT_HOLDER = new ThreadLocal<>();

    /**
     * 设置当前线程的 SecurityContext
     * 
     * @param context 安全上下文
     */
    public static void setContext(SecurityContext context) {
        CONTEXT_HOLDER.set(context);
    }

    /**
     * 获取当前线程的 SecurityContext
     * 
     * @return 安全上下文（未登录时返回 null）
     */
    public static SecurityContext getContext() {
        return CONTEXT_HOLDER.get();
    }

    /**
     * 获取当前用户ID
     * 
     * @return 用户ID（未登录返回 null）
     */
    public static Long getUserId() {
        SecurityContext context = getContext();
        return context != null ? context.getUserId() : null;
    }

    /**
     * 获取当前用户名
     * 
     * @return 用户名（未登录返回 null）
     */
    public static String getUsername() {
        SecurityContext context = getContext();
        return context != null ? context.getUsername() : null;
    }

    /**
     * 获取当前用户角色集合
     * 
     * @return 角色集合（未登录返回 null）
     */
    public static Set<String> getRoles() {
        SecurityContext context = getContext();
        return context != null ? context.getRoles() : null;
    }

    /**
     * 判断当前用户是否已登录
     * 
     * @return true=已登录
     */
    public static boolean isAuthenticated() {
        SecurityContext context = getContext();
        return context != null && context.isAuthenticated();
    }

    /**
     * 判断当前用户是否拥有某个角色
     * 
     * @param role 角色编码
     * @return true=拥有该角色
     */
    public static boolean hasRole(String role) {
        SecurityContext context = getContext();
        return context != null && context.hasRole(role);
    }

    /**
     * 清理当前线程的 SecurityContext
     * 
     * 【重要】必须在请求结束时调用，避免内存泄漏
     * 通常在 Filter 的 finally 块中调用
     */
    public static void clear() {
        CONTEXT_HOLDER.remove();
    }
}