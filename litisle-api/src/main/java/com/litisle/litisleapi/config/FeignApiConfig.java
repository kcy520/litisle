package com.litisle.litisleapi.config;

import feign.Logger;
import feign.Request;
import org.springframework.context.annotation.Bean;

/**
 * Feign 全局配置（日志级别、连接超时等）。
 *
 * 【设计说明】
 * 1. 本类为纯配置类，不标注 @Configuration，避免被组件扫描后对所有 Feign 客户端全局生效
 * 2. 通过 @FeignClient(configuration = FeignApiConfig.class) 显式引用，仅对引用它的客户端生效
 * 3. 各业务服务依赖 litisle-api 后，其 Feign 客户端自动获得统一的日志与超时配置
 *
 * Feign 日志 光配 Level 没用
 * 各服务 application.yml 必须加：
 * logging:
 *   level:
 *     com.litisle.litisleapi.feign: DEBUG
 * 放client的这个包
 *
 * FeignApiConfig 没有 @Configuration
不会被 @ComponentScan 扫到
它是 Feign 自己通过 FeignClientsConfiguration 机制加载的
作用范围：只影响当前 Feign Client 
 * 
 * @author litisle
 */
public class FeignApiConfig {

    /**
     * Feign 日志级别
     * NONE：不记录（默认）
     * BASIC：仅记录请求方法、URL、响应状态码与执行时间（推荐生产使用）
     * HEADERS：在 BASIC 基础上记录请求/响应头
     * FULL：记录全部（含请求/响应体），仅调试使用
     */
    @Bean
    public Logger.Level feignLoggerLevel() {
        return Logger.Level.BASIC;
    }

    /**
     * Feign 请求超时配置
     * connectTimeoutMillis：连接超时（毫秒）
     * readTimeoutMillis：读取超时（毫秒）
     */
    @Bean
    public Request.Options feignRequestOptions() {
        return new Request.Options(
                5000,   // connectTimeout
                10000,  // readTimeout
                true    // followRedirects
        );
    }
}
