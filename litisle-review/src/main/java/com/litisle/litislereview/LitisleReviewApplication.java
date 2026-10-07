package com.litisle.litislereview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients(basePackages = "com.litisle.litisleapi.feign")
public class LitisleReviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(LitisleReviewApplication.class, args);
    }

}
