package com.litisle.litislefile;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling：对账任务（见 FileReconcileTask，每日 02:00）已启用
@SpringBootApplication
@EnableScheduling
public class LitisleFileApplication {

    public static void main(String[] args) {
        SpringApplication.run(LitisleFileApplication.class, args);
    }

}

/*
@SpringBootApplication
@EnableScheduling//启用定时任务
//user-service 如果是被调用方（只提供接口实现），它不需要这个注解；但如果有其他模块（比如 order 模块）要通过这个 Feign 客户端调用 user 服务，那么 order 模块的启动类必须加 @EnableFeignClients。
@EnableFeignClients(basePackages = "com.litisle.litisleapi.feign")
//@EnableFeignClients(basePackages = "com.demo.api.client", defaultConfiguration = FeignApiConfig.class)
*/
