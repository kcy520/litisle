package com.litisle.litisleauth.config;


import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc 分组配置
 *
 * 【背景】knife4j-next 5.x 不再处理 yml 中 knife4j.openapi.group 的分组声明（upstream 4.5.0
 * 由 Knife4jOpenApiCustomizer 实现，fork 重写后该行为缺失），导致 /v3/api-docs/default
 * 端点不存在、swagger-config 无 groups 字段，React 版 UI 首页拉取文档 404。
 * 故改用 springdoc 原生 GroupedOpenApi 显式注册分组。
 *
 * 【注意】与 application.yml 中 knife4j.openapi.group 段功能重叠，分组注册以此类为准；
 * yml 中的 title/description/version 等文档元信息仍由 knife4j 正常处理
 */
@Configuration
public class SpringDocGroupConfig {

    /**
     * 注册 default 分组，扫描 auth 模块 controller 包
     */
    @Bean
    public GroupedOpenApi defaultGroup() {
        return GroupedOpenApi.builder()
                .group("default")
                .packagesToScan("com.litisle.litisleauth.controller")
                .build();
    }
}