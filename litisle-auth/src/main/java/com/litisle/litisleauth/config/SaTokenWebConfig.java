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
 * 【白名单说明（内部契约端点）】
 * /api/feign/** 是 AuthClient 的服务间契约端点（处罚权威态回查、强制下线、改邮箱校验、
 * 禁言校验），Feign 服务端线程无用户会话（MQ 消费者、定时任务调用时 FeignAuthInterceptor
 * 仅透传 traceId 无 token），若纳入拦截会返回业务码 10002 打断 review/content 的调用链路；
 * 对外安全性由网关保证：网关路由表只按 /api/auth/**、/api/user/** 等业务前缀转发，
 * 没有 /api/feign/** 路由，公网请求直接 404，仅服务间可达
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
                        "/api/auth/captcha/**",
                        "/api/auth/refresh",
                        "/api/auth/oauth/**",
                        "/api/auth/sms/**",
                        // ===== Knife4j 文档（全部放行）=====
                        "/doc.html",
                        "/v3/api-docs/**",
                        "/webjars/**",
                        "/swagger-resources/**",
                        "/swagger-ui/**",
                        "/favicon.ico",

                        // ===== 健康检查 =====
                        "/actuator/**",

                        // ===== 内部契约端点（Feign 服务端线程无用户会话，网关无该前缀路由，公网 404）=====
                        "/api/feign/**"
                );
    }
}
