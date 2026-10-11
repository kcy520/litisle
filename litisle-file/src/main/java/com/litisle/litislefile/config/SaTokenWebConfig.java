package com.litisle.litislefile.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 拦截器配置（file 模块）
 *
 * 【说明】
 * 注册 SaInterceptor 才能让 @SaCheckLogin/@SaCheckRole 注解生效
 * （对齐 auth/user/review 模块 SaTokenWebConfig 口径）
 * 全局拦截所有路径，白名单路径放行
 *
 * 【背景】
 * file 模块的上传/访问/管理 Controller 已使用注解式鉴权（方法级 @SaCheckLogin，
 * 数据级 owner/ADMIN 在 Service 层落地），但此前未注册 SaInterceptor，注解不生效——
 * 任意请求可绕过登录闸门直连服务端口。本配置补齐该缺口，使注解真正强制执行
 *
 * 【与 common-security 的关系】
 * common-security 自动装配提供 StpInterface（角色/权限数据源）、SaTokenExceptionHandler（鉴权异常
 * 转统一 Result），但不负责注册拦截器——拦截器与各服务的路径白名单强相关，由各服务自行声明
 *
 * 【白名单说明】
 * 1. 文档与健康检查：纵深防御口径，防止服务端口被直连时被拦截（网关已不路由）
 * 2. /api/feign/**：FileClient 内部契约端点必须放行——Feign 服务端线程无用户会话
 *   （MQ 消费者取图、定时任务等调用场景 FeignAuthInterceptor 仅透传 traceId 无 token），
 *    其鉴权由"网关不路由 + 仅服务内可达"保证（见 FileFeignProviderController 设计说明）；
 *    若纳入拦截将直接打断 content/review 等服务的跨服务调用链路
 *
 * @author litisle
 */
@Configuration
public class SaTokenWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册 Sa-Token 拦截器，打开注解式鉴权功能
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
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

                        // ===== 内部契约端点（无用户会话，靠网关不路由防护）=====
                        "/api/feign/**"
                );
    }
}
