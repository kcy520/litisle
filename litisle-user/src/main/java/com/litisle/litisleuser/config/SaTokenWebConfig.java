package com.litisle.litisleuser.config;


import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 拦截器配置（user 模块）
 *
 * 【说明】
 * 注册 SaInterceptor 才能让 @SaCheckLogin/@SaCheckRole/@SaCheckPermission 注解生效
 * （对齐 auth/review 模块 SaTokenWebConfig 口径）
 * 全局拦截所有路径，白名单路径放行
 *
 * 【背景】
 * user 模块的 UserAdminController 等接口已使用注解式鉴权（类级 @SaCheckRole + 方法级
 * @SaCheckPermission），但此前未注册 SaInterceptor，注解不生效——任意登录用户可绕过
 * 角色闸门直连后台管理接口。本配置补齐该缺口，使注解真正强制执行
 *
 * 【与 common-security 的关系】
 * common-security 自动装配提供 StpInterface（角色/权限数据源）、SaTokenExceptionHandler（鉴权异常
 * 转统一 Result），但不负责注册拦截器——拦截器与各服务的路径白名单强相关，由各服务自行声明
 *
 * 【白名单说明】
 * user 模块全部接口均为登录/角色后用（无 @Anonymous 匿名端点），
 * 白名单仅保留文档与健康检查；网关已将 /api/user/** /api/admin/** 全部纳入认证，
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