package com.litisle.litislecontent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 内容服务启动类
 * <p>@EnableFeignClients 扫描 api 契约包，注册 FileClient/UserClient/ContentClient 等 Feign Bean
 * （对齐 review 主类口径；缺此注解则 ArticleServiceImpl 注入 FileClient 启动报 NoSuchBeanDefinition）</p>
 */
@EnableFeignClients(basePackages = "com.litisle.litisleapi.feign")
@SpringBootApplication
public class LitisleContentApplication {

    public static void main(String[] args) {
        SpringApplication.run(LitisleContentApplication.class, args);
    }

}
