package com.litisle.commonsecurity.anonymous;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 匿名访问路径扫描器（注解驱动核心）
 *
 * 【设计说明】
 * 1. 监听 Spring 容器刷新完成事件（ContextRefreshedEvent）
 * 2. 遍历所有 Controller 方法，检查 @Anonymous 注解（类级或方法级）
 * 3. 提取注解对应方法的 URL 模式，注册到 AnonymousAccessHandler
 * 4. 与 yml 配置合并，形成最终白名单
 *
 * 【企业规范：声明式编程】
 * - 注解驱动是 Spring 生态主流（@Transactional、@Cacheable 都是注解驱动）
 * - 配置跟着代码走，新增匿名接口只需加注解，不会遗漏
 * - 符合"约定优于配置"原则
 *
 * 【为什么用 ContextRefreshedEvent】
 * - 此时 RequestMappingHandlerMapping 已完成初始化，所有 Controller 已注册
 * - 比 @PostConstruct 更晚，保证依赖的 HandlerMapping 可用
 * - 比 ApplicationReadyEvent 更早，避免首次请求时白名单还没就绪
 *
 * 【handlerMapping 注入方式】
 * - 用 @Autowired(required=false) 字段注入，而非构造器注入
 * - 原因：网关是 WebFlux，没有 RequestMappingHandlerMapping Bean
 * - required=false 让网关也能启动（handlerMapping 为 null 时跳过扫描）
 * - 业务服务（Spring MVC）有此 Bean，正常注入并扫描
 *
 * 【@Anonymous 作用范围】
 * - 类级：该 Controller 所有方法都加入白名单
 * - 方法级：仅该方法加入白名单
 * - 同时标注：方法级生效（不影响结果，已包含）
 *
 * 【URL 提取原理】
 * - Spring MVC 通过 RequestMappingHandlerMapping 管理 @RequestMapping
 * - RequestMappingInfo.getPatternValues() 获取 URL 模式（Spring 5.3+）
 * - 一个方法可能有多个 URL（如 @GetMapping({"/a", "/b"})）
 *
 * 【限制说明】
 * - 仅适用于 Spring MVC（Servlet），不适用 WebFlux（网关需另外实现）
 * - 网关侧的白名单收集方案：后续通过 Feign 调用各服务的 /actuator/anonymous-paths 端点
 *
 * @author litisle
 */
@Slf4j
public class AnonymousPathScanner implements ApplicationListener<ContextRefreshedEvent> {

    /**
     * 匿名访问处理器（用于合并扫描结果和 yml 配置）
     */
    private final AnonymousAccessHandler anonymousAccessHandler;

    /**
     * Spring MVC 的请求映射处理器
     * 【说明】required=false：网关（WebFlux）无此 Bean 时为 null，跳过扫描
     */
    @Autowired(required = false)
    private RequestMappingHandlerMapping handlerMapping;

    /**
     * 是否已扫描过（防止 ContextRefreshedEvent 在父子容器中触发多次）
     */
    private volatile boolean scanned = false;

    public AnonymousPathScanner(AnonymousAccessHandler anonymousAccessHandler) {
        this.anonymousAccessHandler = anonymousAccessHandler;
    }

    /**
     * 容器刷新完成时触发扫描
     *
     * “容器刷新” = Spring 把所有的 Bean 都创建完、依赖注入完、初始化完，并且把所有 @RequestMapping 都解析完的那个“最终时刻”。
     *
     * 【防重复执行说明】
     * - Spring 父子容器（如 Spring MVC）可能触发多次 ContextRefreshedEvent
     * - 用 volatile boolean 标记保证只扫描一次
     * - 即使重复扫描，结果幂等（合并去重）
     *
     * @param event 上下文刷新事件
     */
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (scanned) {
            return;
        }
        scanned = true;

        // 非 MVC 环境（如网关 WebFlux），handlerMapping 为 null，跳过
        if (handlerMapping == null) {
            log.info("当前环境非 Spring MVC，跳过 @Anonymous 注解扫描（可能为网关）");
            return;
        }

        Set<String> annotationPaths = scanAnonymousPaths();
        anonymousAccessHandler.refreshPaths(annotationPaths);
    }

    /**
     * 扫描所有标注 @Anonymous 的 Controller 方法，提取 URL
     *
     * 【扫描流程】
     * 1. 获取所有 HandlerMethod（Spring 注册的 Controller 方法）
     * 2. 对每个方法检查：类是否有 @Anonymous、方法是否有 @Anonymous
     * 3. 命中则提取该方法的 URL 模式
     * 4. 收集到 Set 中（自动去重）
     *
     * @return 匿名访问路径集合
     */
    private Set<String> scanAnonymousPaths() {
        Set<String> paths = new HashSet<>();
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = handlerMapping.getHandlerMethods();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMethods.entrySet()) {
            HandlerMethod handlerMethod = entry.getValue();
            RequestMappingInfo mappingInfo = entry.getKey();

            // 检查类级或方法级是否有 @Anonymous 注解
            if (hasAnonymousAnnotation(handlerMethod)) {
                // 提取 URL 模式
                Set<String> patterns = mappingInfo.getPatternValues();
                paths.addAll(patterns);
                log.debug("发现匿名接口: {} -> {}", handlerMethod.getMethod().getName(), patterns);
            }
        }

        log.info("@Anonymous 注解扫描完成，共发现 {} 个匿名接口: {}", paths.size(), paths);
        return paths;
    }

    /**
     * 检查方法是否有 @Anonymous 注解（类级或方法级）
     *
     * @param handlerMethod Spring 的 HandlerMethod 封装
     * @return true=允许匿名访问
     */
    private boolean hasAnonymousAnnotation(HandlerMethod handlerMethod) {
        // 方法级注解
        Anonymous methodAnnotation = handlerMethod.getMethodAnnotation(Anonymous.class);
        if (methodAnnotation != null) {
            return true;
        }
        // 类级注解（整个 Controller 都匿名）
        Anonymous classAnnotation = handlerMethod.getBeanType().getAnnotation(Anonymous.class);
        return classAnnotation != null;
    }
}