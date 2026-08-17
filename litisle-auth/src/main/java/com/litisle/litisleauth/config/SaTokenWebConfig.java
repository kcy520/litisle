package com.litisle.litisleauth.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 拦截器配置
 *
 * 【说明】
 * 注册 SaInterceptor 才能让 @SaCheckLogin/@SaCheckRole 注解生效
 * 全局拦截所有路径，白名单路径放行
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
                        // 认证相关（匿名访问）
                        "/api/auth/login",
                        "/api/auth/register",
                        "/api/auth/captcha",
                        "/api/auth/refresh",
                        "/api/auth/oauth/**",
                        "/api/auth/sms/**",
                        // 健康检查
                        "/actuator/**"
                );
    }
}