package com.litisle.litisleai;

import com.litisle.litisleapi.feign.ContentClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * litisle-ai 启动类（AI 能力供给层：模型通道 / 计量配额 / 生成式 Agent 统一收口）
 *
 * <p>【Feign 说明】clients 显式登记 ContentClient（板块二工具①取稿件/②查标签池的数据源），
 * 不用 basePackages 扫描——避免把本服务自己提供的 AiClient 也注册成消费方客户端
 * （AI 是 AiClient 的提供方，不消费它）。</p>
 *
 * @author litisle
 */
@SpringBootApplication
@EnableScheduling
@EnableFeignClients(clients = {ContentClient.class})
public class LitisleAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(LitisleAiApplication.class, args);
    }

}
