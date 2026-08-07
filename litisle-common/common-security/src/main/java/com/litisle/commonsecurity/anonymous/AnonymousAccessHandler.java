package com.litisle.commonsecurity.anonymous;


import com.litisle.commonsecurity.context.SecurityContext;
import com.litisle.commonsecurity.context.SecurityContextHolder;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 匿名访问处理器
 *
 * 【设计说明】
 * 1. 判断请求路径是否在匿名白名单中（注解扫描 + yml 配置合并）
 * 2. 为匿名请求构建匿名 SecurityContext，业务层可据此判断登录状态
 * 3. 网关和业务服务均可使用：网关用于跳过 JWT 校验，业务服务用于构建上下文
 *
 * 【白名单合并策略】
 * - 来源1：yml 配置 litisle.security.anonymous.paths（AnonymousProperties）
 * - 来源2：@Anonymous 注解扫描的路径（由业务服务启动时扫描并注入此 Handler）
 * - 两者合并为最终白名单，去重后缓存
 *
 * 【匿名上下文设计】
 * - userId = null：表示未登录
 * - roles = ["ANONYMOUS"]：拥有匿名角色，可用于区分"匿名用户"和"已登录用户"
 * - authenticated = false：未认证状态
 *
 * 【AntPathMatcher 说明】
 * - Spring 提供的路径匹配器，支持 Ant 风格通配符
 * - 线程安全，可全局复用
 * - 比正则更简单，比精确匹配更灵活
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnonymousAccessHandler {

    /**
     * 匿白名单路径集合（线程安全）
     * 【说明】使用 volatile + 不可变集合保证可见性和线程安全
     * volatile 保证的是：一个线程修改了变量，另一个线程能立刻看到最新值，
     * 并且"看到变量的那一刻，它指向的对象已经完全构造好了"。
     */
    private volatile Set<String> anonymousPaths = Collections.emptySet();

    /**
     * Ant 路径匹配器（Spring 内置，线程安全）
     */
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * yml 配置的匿名路径（兑底，用于无法加注解的场景）
     */
    private final AnonymousProperties anonymousProperties;

    /**
     * 匿名用户角色标识
     */
    private static final String ANONYMOUS_ROLE = "ANONYMOUS";

    /**
     * 兜底初始化：加载 yml 配置到白名单
     *
     * 【企业规范：Fail-Fast 原则】
     * - Bean 初始化完成后状态必须可用，不依赖外部调用
     * - 启动时加载 yml 配置，配置错误立即暴露
     * - 防御性编程：避免空白名单导致的安全风险（本该匿名的接口被拦截）
     *
     * 【调用顺序】
     * 1. @PostConstruct 先加载 yml 配置（本方法）
     * 2. AnonymousPathScanner 在 ContextRefreshedEvent 时调用 refreshPaths 合并注解结果
     * 3. 即使没有扫描器（如单元测试），yml 配置也能生效
     */
    @PostConstruct
    public void init() {
        refreshPaths(Collections.emptySet());
    }

    /**
     * 判断请求路径是否允许匿名访问
     *
     * 【匹配流程】
     * 1. 遍历白名单所有路径模式
     * 2. 使用 AntPathMatcher 匹配，命中任一即返回 true
     * 3. 全部不命中返回 false（需要 JWT 校验）
     *
     * @param requestPath 请求路径（如 /api/content/article/123）
     * @return true=允许匿名访问
     */
    public boolean isAnonymous(String requestPath) {
        if (requestPath == null || requestPath.isEmpty()) {
            return false;
        }
        Set<String> paths = this.anonymousPaths;
        for (String pattern : paths) {
            if (pathMatcher.match(pattern, requestPath)) {
                return true;
            }
        }
        return false;
    }


    /**
     * 为匿名请求构建匿名 SecurityContext
     *
     * 【使用场景】
     * - 业务服务在 Filter 中检测到匿名请求（无 X-User-Id Header）时调用
     * - 构建匿名上下文后，业务代码可通过 SecurityContextHolder 获取"匿名用户"信息
     * - @RequireRole 注解的接口会拦截匿名用户（因 authenticated=false）
     *
     * 【为什么需要匿名上下文】
     * - 避免 NPE：业务代码调用 SecurityContextHolder.getUserId() 时返回 null 而非抛异常
     * - 角色区分：匿名用户拥有 ANONYMOUS 角色，可针对匿名用户做特殊逻辑（如限流更严格）
     */
    public void setupAnonymousContext() {
        SecurityContext anonymousContext = new SecurityContext();
        anonymousContext.setUserId(null);
        anonymousContext.setUsername("anonymous");
        Set<String> roles = new HashSet<>();
        roles.add(ANONYMOUS_ROLE);
        anonymousContext.setRoles(roles);
        // 【说明】SecurityContext.isAuthenticated() 是基于 userId 是否为 null 的只读判断，
        // 没有 authenticated 字段，无需也无法调用 setAuthenticated。
        // 这里 userId 已设为 null，isAuthenticated() 自然返回 false。
        SecurityContextHolder.setContext(anonymousContext);
    }

    /**
     * 刷新匿名白名单（合并 yml 配置 + 注解扫描路径）
     *
     * 【调用时机】
     * 1. 应用启动时（@PostConstruct 兑底加载 yml）
     * 2. AnonymousPathScanner 扫描注解完成后调用（合并注解结果）
     * 3. 配置热更新时（Nacos 监听变更，后续实现）
     *
     * 【TODO - 后续实现：网关侧白名单收集】
     * 网关是 WebFlux，无法用 RequestMappingHandlerMapping 扫描。
     * 后续方案：
     * 1. 业务服务暴露 /actuator/anonymous-paths 端点返回本服务白名单
     * 2. 网关启动时通过 Feign 调用各服务的端点收集全局白名单
     * 3. 结合服务发现（Nacos）动态感知服务上下线
     * 当前阶段网关先用 yml 配置作为兑底，后续优化为自动收集
     *
     * @param annotationPaths 通过 @Anonymous 注解扫描到的路径集合
     */
    public void refreshPaths(Set<String> annotationPaths) {
        Set<String> merged = new HashSet<>();
        // 合并 yml 配置
        if (anonymousProperties != null && anonymousProperties.getPaths() != null) {
            merged.addAll(anonymousProperties.getPaths());
        }
        // 合并注解扫描结果
        if (annotationPaths != null) {
            merged.addAll(annotationPaths);
        }
        // volatile 引用切换，保证线程安全
        this.anonymousPaths = Collections.unmodifiableSet(merged);
        log.info("匿名白名单刷新完成，共 {} 条: {}", merged.size(), merged);
    }
}