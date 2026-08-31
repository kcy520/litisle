package com.litisle.litisleuser;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 一键启动docker容器
cd /d e:\NewJavaPro\LitIsle  &&  docker-compose up -d
*一键关闭docker容器
docker-compose stop

重新挂载
 docker-compose down -v
 docker-compose up -d


 /**
 ---------------------------------------------------------------------
 knife4j-url:
 http://localhost:8081/doc.html

 ---------------------------------------------------------------------
 docker-compose
 cd /d e:\NewJavaPro\LitIsle && docker-compose start

 ---------------------------------------------------------------------
 docker exec -it litisle-redis redis-cli -a 123

 ---------------------------------------------------------------------
 sentinel:
 cd /d E:\importantFile\sentinel && java -Dserver.port=8090 -Dcsp.sentinel.dashboard.server=localhost:8090 -Dproject.name=sentinel-dashboard -jar sentinel-dashboard-1.8.9.jar

 ---------------------------------------------------------------------
 重建compose:
 cd /d E:\NewJavaPro\LitIsle
 docker-compose down
 docker-compose up -d

 //自动创建缺失的容器
 cd /d e:\NewJavaPro\LitIsle &&  docker-compose up -d

 ---------------------------------------------------------------------
 RabbitMQ:
 http://localhost:15672

 查看日志
 docker logs --tail 60 ms-demo-rabbitmq


 ---------------------------------------------------------------------
 控制台清屏
 cls

 ---------------------------------------------------------------------

 版本
 Elasticsearch-Kibana-IK分词器
 8.18.8
 网络：
 es-net

 ---------------------------------------------------------------------

 执行初始化sql
 docker exec -i litisle-mysql mysql -uroot -pverysecret < docker/init/nacos/mysql-schema.sql


 */




@SpringBootApplication
@EnableScheduling//启用定时任务
//user-service 如果是被调用方（只提供接口实现），它不需要这个注解；但如果有其他模块（比如 order 模块）要通过这个 Feign 客户端调用 user 服务，那么 order 模块的启动类必须加 @EnableFeignClients。
@EnableFeignClients(basePackages = "com.litisle.litisleapi.feign")
//@EnableFeignClients(basePackages = "com.demo.api.client", defaultConfiguration = FeignApiConfig.class)
public class LitisleUserApplication {

    public static void main(String[] args) {
        SpringApplication.run(LitisleUserApplication.class, args);
    }

}
