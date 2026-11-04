package com.litisle.litislesearch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * litisle-search 搜索服务启动类：
 * 站内检索统一收口方（ES 索引同步 + 全文/多维度检索 + dense_vector KNN 相似推荐），
 * 架构见 docs/Architecture/Search-Architecture.md
 *
 * @EnableFeignClients：扫描 litisle-api 契约包，注册 ContentClient 等消费方 Feign Bean
 * （published 回查元数据/降级热门列表/标签联想聚合等场景使用，批次二起消费）；
 * 本服务同时是 SearchClient 的提供方（/api/feign/search/** 由本服务实现，Provider 侧无需额外注解）
 * @EnableScheduling：启用定时任务（批次三 3.2 IndexHeatFlushJob 热度刷写；
 * 批次四起热点缓存清理等任务同样复用此开关）
 * @EnableAsync：启用异步执行（批次六 6.2 搜索历史异步落库，避免拖慢检索主链路）
 */
@EnableFeignClients(basePackages = "com.litisle.litisleapi.feign")
@EnableScheduling
@EnableAsync
@SpringBootApplication
public class LitisleSearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(LitisleSearchApplication.class, args);
    }

}
