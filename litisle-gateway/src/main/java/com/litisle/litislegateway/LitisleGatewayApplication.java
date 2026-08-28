package com.litisle.litislegateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 *  优先把网关限流配好（IP 级、路由级），这是性价比最高的防护。
 *  只有网关做不到的细粒度场景（基于请求体参数、基于用户ID、基于失败次数），才用 Redis 注解补充。
 */
@SpringBootApplication
public class LitisleGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(LitisleGatewayApplication.class, args);
    }

}
