package com.litisle.litisleapi.config;

import com.litisle.litisleapi.fallback.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * todo Feign 降级工厂自动配置  -未生效
 *
 * 【机制】
 * 1. 通过 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports 注册，
 *    依赖 litisle-api 的业务服务（auth/user 等）启动时自动加载本配置，业务服务零改动
 * 2. 将 fallback/ 包的两个 FallbackFactory 注册为【调用方容器】中的 Bean，
 *    满足 @FeignClient(fallbackFactory = Xxx.class) 对"工厂必须是 Spring Bean"的要求
 * 3. 不会重复注册：业务服务只扫描自己的包（如 com.litisle.litisleauth），
 *    扫不到 com.litisle.litisleapi.config，与 FeignApiConfig 的"显式引用、不全局生效"设计互补
 *
 * 【生效前提】
 * - 调用方必须启用 Sentinel 的 Feign 适配，否则 fallbackFactory 不生效（缺适配时降级是"死"的）：
 *   ① pom 添加 spring-cloud-starter-alibaba-sentinel（版本由父 pom 的 spring-cloud-alibaba 管理）
 *   ② yml 开关（建议放 Nacos 共享模板）：
 *      feign.sentinel.enabled: true
 *      ★ 唯一生效的属性名（SentinelFeignAutoConfiguration 源码 @ConditionalOnProperty 实证），
 *      位于顶层 feign.* 而非 spring.cloud.openfeign 下；
 *      spring.cloud.openfeign.sentinel.enabled 无效但无害，可留作向前兼容 ---------建议核对此处注释
 * - Sentinel 无 TimeLimiter 问题，慢调用治理走 Dashboard 的 RT 熔断规则；
 *   注意熔断规则默认不持久化，需接 Nacos 数据源（sentinel-datasource-nacos）
 *
 * @author litisle
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(FallbackFactory.class)
public class FeignFallbackAutoConfiguration {

    //调用方配套改动（涉及 auth/user 两个服务，按你的习惯：依赖进 pom、配置进 Nacos 共享模板）：
    //
    //    pom（auth 和 user 都加）：
    //<dependency>
    //    <groupId>com.alibaba.cloud</groupId>
    //    <artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>
    //</dependency>
    //
    //yml（Nacos 共享模板）：
    // spring:
    //  cloud:
    //    openfeign:
    //      sentinel:
    //        enabled: true        # 启用后 fallbackFactory 才生效（SCA 2025.0.0.0 属性名）
    //    sentinel:
    //      transport:
    //        dashboard: localhost:8090   # 指向 Docker 部署的 Sentinel Dashboard
    //        port: 8719                  # 心跳端口，多服务同机部署需错开（如 8719/8720...）

    @Bean
    @ConditionalOnMissingBean
    public UserClientFallbackFactory userClientFallbackFactory() {
        return new UserClientFallbackFactory();
    }

    @Bean
    @ConditionalOnMissingBean
    public AuthClientFallbackFactory authClientFallbackFactory() {
        return new AuthClientFallbackFactory();
    }

    @Bean
    @ConditionalOnMissingBean
    public FileClientFallbackFactory fileClientFallbackFactory() {
        return new FileClientFallbackFactory();
    }

    @Bean
    @ConditionalOnMissingBean
    public AiClientFallbackFactory aiClientFallbackFactory() {
        return new AiClientFallbackFactory();
    }

    @Bean
    @ConditionalOnMissingBean
    public ContentClientFallbackFactory contentClientFallbackFactory() {
        return new ContentClientFallbackFactory();
    }

    @Bean
    @ConditionalOnMissingBean
    public SocialClientFallbackFactory messageClientFallbackFactory() {
        return new SocialClientFallbackFactory();
    }

    /**
     * 注册 SearchClient 降级工厂（temp search批次B B1）：
     * 供 @FeignClient(fallbackFactory = SearchClientFallbackFactory.class) 注入，
     * 相似推荐/服务间检索核心读依赖 fail-fast 语义见工厂类注释
     */
    @Bean
    @ConditionalOnMissingBean
    public SearchClientFallbackFactory searchClientFallbackFactory() {
        return new SearchClientFallbackFactory();
    }
}