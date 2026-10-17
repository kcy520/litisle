package com.litisle.litislecontent.config;


import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 拦截器配置（content 模块）
 *
 * 【说明】
 * 注册 SaInterceptor 才能让 @SaCheckLogin/@SaCheckRole 注解生效
 * （对齐 auth/user/file/review 模块 SaTokenWebConfig 口径）
 *
 * 【与 common-security 的关系】
 * common-security 自动装配提供 StpInterface（角色/权限数据源）、SaTokenExceptionHandler（鉴权异常
 * 转统一 Result）、AnonymousAccessHandler（匿名白名单），但不负责注册拦截器——
 * 拦截器与各服务的路径白名单强相关，由各服务自行声明
 *
 * 【白名单说明（与 review/user 的差异点）】
 * content 存在公开读端点（文章列表/详情/热门/分类，游客可访问公开文章），
 * 网关已将对应路径纳入 JWT 免校验白名单；此处为服务端口被直连时的纵深防御
 *
 * 【为何不用静态 excludePathPatterns 放行文章详情】
 * 网关白名单 /api/content/article/* 中的 * 通配会波及同前缀的 POST 写接口
 * （/article/publish 发布、/article/{id}/submit 重提等）；拦截器 excludePathPatterns
 * 同样只按路径不区分 HTTP 方法，静态放行会形成"匿名发布"漏洞。
 * 故在 handle 回调中方法感知放行：仅 GET 的单段路径视为详情匿名放行，
 * 其余一律 checkLogin（注解鉴权仍由 SaInterceptor 默认开启的 isAnnotation 处理，不受影响）
 *
 * 【注意】
 * 文章详情接口（GET /article/{id}）不得再加 @SaCheckLogin，
 * 公开/私密判定由 service 层可见性判定链收口（visibility：1-公开/2-仅自己/3-仅关注者/4-密码）
 *
 * @author litisle
 */
@Configuration
public class SaTokenWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册 Sa-Token 拦截器，打开注解式鉴权功能 + 自定义登录校验链
        registry.addInterceptor(new SaInterceptor(handle -> {
            // 1. 公开读接口精确放行：列表/热门/分类（路径唯一、无同名写接口，静态匹配安全）
            String path = SaHolder.getRequest().getRequestPath();
            if ("/api/content/article/list".equals(path)
                    || "/api/content/article/hot".equals(path)
                    || path.startsWith("/api/content/category/")) {
                return;
            }

            // 2. 文章详情匿名放行：仅 GET 的单段路径（/api/content/article/{id}）
            //    POST /article/publish、/article/{id}/submit 等写接口仍强制登录，
            //    防 * 通配把发布接口误放行为匿名可访问
            String method = SaHolder.getRequest().getMethod();
            if ("GET".equalsIgnoreCase(method) && path.matches("^/api/content/article/[^/]+$")) {
                return;
            }

            // 3. 其余路径一律登录校验
            StpUtil.checkLogin();
        }))
                .addPathPatterns("/**")
                .excludePathPatterns(
                        // ===== Knife4j 文档（全部放行）=====
                        "/doc.html",
                        "/v3/api-docs/**",
                        "/webjars/**",
                        "/swagger-resources/**",
                        "/swagger-ui/**",
                        "/favicon.ico",

                        // ===== 健康检查 =====
                        "/actuator/**",

                        // ===== Feign 服务间内部端点（放行）=====
                        // /api/feign/** 仅供微服务内部调用（review 回查正文 ContentClient、
                        // social 回写冗余计数等），Feign 请求不携带用户登录态，若不排除会被
                        // checkLogin 拦截（NotLoginException→Result.fail），导致调用方把
                        // "服务间鉴权失败"误判为业务失败；安全边界：网关不转发 /api/feign/** 到外部
                        "/api/feign/**"
                );
    }
}