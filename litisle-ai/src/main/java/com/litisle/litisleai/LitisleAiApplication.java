package com.litisle.litisleai;

import com.litisle.litisleai.config.AiBudgetProperties;
import com.litisle.litisleai.config.AiQuotaProperties;
import com.litisle.litisleapi.feign.ContentClient;
import com.litisle.litisleapi.feign.SearchClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * litisle-ai 启动类（AI 能力供给层：模型通道 / 计量配额 / 生成式 Agent 统一收口）
 *
 * <p>【Feign 说明】clients 显式登记 ContentClient（板块二工具①取稿件/②查标签池的数据源）与
 * SearchClient（板块八 1.1/1.2：条件版相似检索 + 作者名解析 user_idx 查询的数据源），
 * 不用 basePackages 扫描——避免把本服务自己提供的 AiClient 也注册成消费方客户端
 * （AI 是 AiClient 的提供方，不消费它）。</p>
 *
 * @author litisle
 */
@SpringBootApplication
@EnableScheduling
// 显式注册计量/预算配置 Bean：两类只有 @ConfigurationProperties 注解，未注册会导致注入失败（启动报 NoSuchBeanDefinitionException）
@EnableConfigurationProperties({AiBudgetProperties.class, AiQuotaProperties.class})
@EnableFeignClients(clients = {ContentClient.class, SearchClient.class})
public class LitisleAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(LitisleAiApplication.class, args);
    }

}
