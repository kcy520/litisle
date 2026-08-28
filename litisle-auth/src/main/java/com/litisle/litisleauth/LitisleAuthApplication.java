package com.litisle.litisleauth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients(basePackages = "com.litisle.litisleapi.feign")
// UserClient 是定义在 litisle-api 模块中的 @FeignClient 接口，litisle-auth 模块作为使用方，启动类必须添加 @EnableFeignClients 来告诉 Spring Cloud OpenFeign 需要扫描并创建 Feign 客户端的代理 Bean。如果不加注解，Spring 容器中就没有 UserClient 的 Bean，所以 IDE 报 "Incorrect autowiring" 警告。
public class LitisleAuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(LitisleAuthApplication.class, args);
    }

}

