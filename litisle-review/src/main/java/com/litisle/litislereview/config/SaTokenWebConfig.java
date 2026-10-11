package com.litisle.litislereview.config;


import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 拦截器配置（review 模块）
 *
 * 【说明】
 * 注册 SaInterceptor 才能让 @SaCheckLogin/@SaCheckRole 注解生效（对齐 auth 模块 SaTokenWebConfig 口径）
 * 全局拦截所有路径，白名单路径放行
 *
 * 【与 common-security 的关系】
 * common-security 自动装配提供 StpInterface（角色/权限数据源）、SaTokenExceptionHandler（鉴权异常
 * 转统一 Result），但不负责注册拦截器——拦截器与各服务的路径白名单强相关，由各服务自行声明
 *
 * 【白名单说明】
 * review 模块全部接口均为登录/角色后用（无 @Anonymous 匿名端点），
 * 白名单仅保留文档与健康检查；网关已将 /api/review/** 全部纳入认证，
 * 此处白名单为纵深防御口径，防止服务端口被直连时文档/监控端点被拦截
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
                        "/actuator/**"
                );
    }
}