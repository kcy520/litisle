package com.litisle.litislereview.config;

import com.litisle.litislereview.interceptor.ModerationGlobalQpsInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 审核服务 Web MVC 配置（成本护栏 §2.6：全站 QPS 拦截器注册）
 *
 * 【与 SaTokenWebConfig 分开的原因】
 * 职责单一：SaTokenWebConfig 只管鉴权拦截器，本类只管限流拦截器；
 * 拦截器执行顺序按注册顺序（同一 WebMvcConfigurer 内先注册先执行），
 * 鉴权在前限流在后可先挡未登录流量；两者分文件避免后续各自演进互相牵连。
 *
 * 【拦截路径】
 * 仅拦 /api/review/** 业务路径：文档（doc.html 等）与健康检查（/actuator/**）
 * 不在该前缀下，天然放行，与 SaTokenWebConfig 白名单口径一致。
 *
 * @author litisle
 */
@Configuration
@RequiredArgsConstructor
public class ModerationWebConfig implements WebMvcConfigurer {

    /** 全站 QPS 拦截器（阈值/窗口外置 litisle.moderation.qps.*） */
    private final ModerationGlobalQpsInterceptor moderationGlobalQpsInterceptor;

    /**
     * 注册全站 QPS 限流拦截器（仅拦业务路径，文档/监控端点天然放行）
     *
     * @param registry 拦截器注册器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 全站 QPS 拦截器：注册在业务前缀上，防审核接口洪峰（含 3-8.3 降级期机审调用量放大防护）
        registry.addInterceptor(moderationGlobalQpsInterceptor)
                .addPathPatterns("/api/review/**");
    }
}