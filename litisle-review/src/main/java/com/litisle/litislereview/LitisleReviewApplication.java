package com.litisle.litislereview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableFeignClients(basePackages = "com.litisle.litisleapi.feign")
@EnableScheduling
public class LitisleReviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(LitisleReviewApplication.class, args);
    }

}
